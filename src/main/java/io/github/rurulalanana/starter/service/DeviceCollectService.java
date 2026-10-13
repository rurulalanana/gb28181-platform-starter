package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备上线后的主动数据采集。
 *
 * <p>设备注册只告诉平台「我在线」，具体有哪些通道、型号是什么、当前状态如何，
 * 都需要平台主动查询。这里在注册/心跳首次发现设备时自动下发一轮查询：
 * <ol>
 *   <li>{@code Catalog} —— 通道目录（最重要，落 {@code gb_channel}）</li>
 *   <li>{@code DeviceInfo} —— 厂商/型号/固件（落 {@code gb_device}）</li>
 *   <li>{@code DeviceStatus} —— 工作状态（落 {@code gb_device_record} 等）</li>
 *   <li>{@code ConfigDownload} —— 基础配置（设备名称、心跳周期等）</li>
 * </ol>
 *
 * <p><b>节流</b>：设备频繁重启会反复注册，这里按设备编号记录最近一次采集时间，
 * {@code collect-interval-seconds} 内不重复采集（默认 300 秒）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCollectService {

    /** 目录采集后等待应答的时长（毫秒），超时则把设备自身登记为通道。 */
    private static final long SELF_CHANNEL_FALLBACK_DELAY_MS = 3000L;

    private final Gb28181SipClientProvider clientProvider;
    private final Gb28181Properties props;
    private final DeviceService deviceService;
    private final PlatformAsyncExecutor asyncExecutor;

    /**
     * 兜底任务调度器。
     *
     * <p>不能复用 {@link PlatformAsyncExecutor}（单线程串行落库），
     * 否则一次 sleep 会阻塞后续所有落库任务。这里用独立的小线程池。
     */
    private final ScheduledExecutorService fallbackScheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "gb28181-fallback");
                t.setDaemon(true);
                return t;
            });

    /** 设备编号 → 最近一次采集时间戳（毫秒）。 */
    private final Map<String, Long> lastCollectAt = new ConcurrentHashMap<>();

    /** 采集次数统计（诊断用）。 */
    private final AtomicInteger collectCount = new AtomicInteger();

    /**
     * 设备上线时触发采集（幂等、节流）。
     *
     * @param deviceId 设备编号
     * @param force    true 表示忽略节流（手动调用 {@code /device/fetchcatalog} 时用）
     */
    public void collectOnOnline(String deviceId, boolean force) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        if (!props.getStarter().isAutoCollect()) {
            log.debug("已关闭自动采集（gb28181.starter.auto-collect=false），跳过 {}", deviceId);
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = props.getStarter().getCollectIntervalSeconds() * 1000L;
        if (!force) {
            Long last = lastCollectAt.get(deviceId);
            if (last != null && now - last < intervalMs) {
                log.debug("设备 {} 距上次采集不足 {}s，跳过", deviceId, intervalMs / 1000);
                return;
            }
        }
        lastCollectAt.put(deviceId, now);
        asyncExecutor.run("设备数据采集 " + deviceId, () -> doCollect(deviceId));
    }

    /** 执行一轮采集。 */
    private void doCollect(String deviceId) {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            log.warn("SIP 客户端不可用，跳过设备数据采集: {}", deviceId);
            return;
        }
        int ok = 0;
        // 1. 通道目录（必须）
        if (trySend("Catalog", deviceId, () -> client.queryCatalog(deviceId))) {
            ok++;
        }
        // 2. 设备信息（厂商/型号/固件）
        if (props.getStarter().isCollectDeviceInfo()
                && trySend("DeviceInfo", deviceId, () -> client.queryDeviceInfo(deviceId))) {
            ok++;
        }
        // 3. 设备状态（在线/离线、录像、告警等）
        if (props.getStarter().isCollectDeviceStatus()
                && trySend("DeviceStatus", deviceId, () -> client.queryDeviceStatus(deviceId))) {
            ok++;
        }
        collectCount.incrementAndGet();
        log.info("已向设备 {} 下发 {} 项查询（目录/信息/状态）", deviceId, ok);
        // 目录应答是异步的；延迟兜底：若设备始终不下发子通道，
        // 把设备自身登记为通道，避免前端通道列表为空
        scheduleSelfChannelFallback(deviceId);
    }

    /**
     * 目录采集兜底：延迟检查是否产生了通道，没有则把设备自身登记为通道。
     *
     * <p>设备对 Catalog 的应答通常在 100ms~2s 内到达，这里等 3 秒后检查一次。
     * <b>注意</b>：不能用 {@link PlatformAsyncExecutor}（单线程）做等待，
     * 否则会阻塞所有后续落库；这里交给独立的调度线程池。
     */
    private void scheduleSelfChannelFallback(String deviceId) {
        fallbackScheduler.schedule(() -> {
            try {
                if (deviceService.ensureSelfChannel(deviceId)) {
                    log.info("设备 {} 未上报子通道，已登记自身为通道", deviceId);
                }
            } catch (Exception e) {
                log.warn("登记自身通道失败: device={} err={}", deviceId, e.getMessage());
            }
        }, SELF_CHANNEL_FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** 单条查询下发，异常不影响其他查询。 */
    private boolean trySend(String name, String deviceId, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception e) {
            log.warn("下发 {} 查询失败: device={} err={}", name, deviceId, e.getMessage());
            return false;
        }
    }

    /** 已采集设备数（诊断用）。 */
    public int collectCount() {
        return collectCount.get();
    }

    /** 清除节流记录（设备注销时调用，下次上线立即重新采集）。 */
    public void forget(String deviceId) {
        if (deviceId != null) {
            lastCollectAt.remove(deviceId);
        }
    }
}
