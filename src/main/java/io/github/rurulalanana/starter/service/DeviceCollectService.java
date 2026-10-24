package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final Gb28181SipClientProvider clientProvider;
    /**
     * 运行时配置（热更新）。
     *
     * <p><b>采集开关与节流间隔必须读它、而不是 {@code props.getStarter()}</b>：
     * 那两项已纳入运行时配置（界面上可改、存库、重启后保留），
     * 若这里仍读 yml 快照，界面上关掉「自动采集」后实际还在采集，
     * 表现为「改了不生效」。
     *
     * <p>原先这里还注入着 {@link Gb28181Properties}，全字段改走运行时配置后
     * 已无任何用途，故一并去掉（留着会让后来的人以为还有 yml 参数在此生效）。
     */
    private final RuntimeConfigService config;

    /**
     * 采集下发专用线程池。
     *
     * <p>采集只做 SIP 下发、<b>不碰数据库</b>，但上千台设备同时上线时，
     * 若排在 {@link PlatformAsyncExecutor}（单线程串行落库）后面，
     * 会把设备注册、目录落库一起堵住——实测千台规模下目录应答被推迟 59 秒，
     * 表现为「设备都注册完了，这边还在慢慢建通道」。
     */
    private final ExecutorService collectExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "gb28181-collect");
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
        if (!config.getBoolean(ConfigKeys.DEVICE_AUTO_COLLECT)) {
            log.debug("已关闭自动采集（device.auto-collect=false），跳过 {}", deviceId);
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = config.getInt(ConfigKeys.DEVICE_COLLECT_INTERVAL_SECONDS) * 1000L;
        if (!force) {
            Long last = lastCollectAt.get(deviceId);
            if (last != null && now - last < intervalMs) {
                log.debug("设备 {} 距上次采集不足 {}s，跳过", deviceId, intervalMs / 1000);
                return;
            }
        }
        lastCollectAt.put(deviceId, now);
        collectExecutor.execute(() -> {
            try {
                doCollect(deviceId);
            } catch (Throwable t) {
                log.warn("设备数据采集失败: {}", deviceId, t);
            }
        });
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
        if (config.getBoolean(ConfigKeys.DEVICE_COLLECT_DEVICE_INFO)
                && trySend("DeviceInfo", deviceId, () -> client.queryDeviceInfo(deviceId))) {
            ok++;
        }
        // 3. 设备状态（在线/离线、录像、告警等）
        if (config.getBoolean(ConfigKeys.DEVICE_COLLECT_DEVICE_STATUS)
                && trySend("DeviceStatus", deviceId, () -> client.queryDeviceStatus(deviceId))) {
            ok++;
        }
        collectCount.incrementAndGet();
        log.info("已向设备 {} 下发 {} 项查询（目录/信息/状态）", deviceId, ok);
        // 通道只认设备上报的目录应答（SipEventBridge → saveCatalog）。
        // 这里刻意不做「等 3 秒没子通道就把设备自身登记为通道」的兜底：
        // 目录应答经常晚于兜底定时器，结果是设备同时留下「自身通道」和真实子通道两条脏数据。
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
