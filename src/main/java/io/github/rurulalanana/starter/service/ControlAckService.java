package io.github.rurulalanana.starter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 设备控制命令的应答等待（GB/T 28181—2022 附录 A.2.6.2 设备控制应答）。
 *
 * <p>为什么需要它：控制类接口过去一律「发出即返回」，回给界面的只有 {@code ok}。
 * 但 {@code <Response><Result>ERROR</Result></Response>} 才是设备真正说了话 ——
 * 设备不支持该命令、参数越界、当前状态不允许，全都在这里。
 * 不解析就只能靠用户看画面猜，而框选跟踪这类「画面变化慢、失败也看不出来」的命令
 * 尤其没法猜。
 *
 * <p><b>标准的边界必须说清</b>：{@code Result=OK} 只表示<b>设备受理了这条命令</b>，
 * 不表示动作已经完成。目标跟踪是否真的锁上目标，标准里<b>没有任何回传通道</b>
 * （A.2.5 通知命令只到「设备软件升级结果」），只能靠画面。
 * 所以本服务输出的是「设备怎么答的」，不编造「跟踪成功」。
 *
 * <p>实现要点：
 * <ul>
 *   <li>按 {@code 通道|SN} 登记，SN 由平台生成，能唯一对上号；</li>
 *   <li>设备可能完全不答（多数云台命令如此），所以等待必须带超时且不能阻塞 SIP 线程；</li>
 *   <li>只保留最近若干条应答，避免长时间运行后无限增长。</li>
 * </ul>
 */
@Slf4j
@Service
public class ControlAckService {

    /** 一条设备控制应答。 */
    public record Ack(int sn, String channelId, String result, long at) {

        /** 设备是否回 {@code OK}。 */
        public boolean ok() {
            return "OK".equalsIgnoreCase(result);
        }
    }

    /** 在途等待上限：超过即整体清理。控制命令不是高频接口，够用即可。 */
    private static final int PENDING_MAX = 512;

    /** 最近应答留存条数（供诊断与界面回显）。 */
    private static final int RECENT_MAX = 256;

    /** 在途等待：{@code 通道|SN} → 结果。 */
    private final Map<String, CompletableFuture<Ack>> pending = new ConcurrentHashMap<>();

    /** 最近收到的应答（LRU，只读用途）。 */
    private final Map<String, Ack> recent = Collections.synchronizedMap(
            new LinkedHashMap<>(RECENT_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Ack> eldest) {
                    return size() > RECENT_MAX;
                }
            });

    private static String key(String channelId, int sn) {
        return channelId + '|' + sn;
    }

    /**
     * 下发前登记一个等待位。
     *
     * <p>必须在 {@code sendMessage} <b>之前</b>调用：设备在局域网里几十毫秒就能回，
     * 先发后登记会漏掉应答，表现为「偶尔超时」。
     */
    public CompletableFuture<Ack> expect(String channelId, int sn) {
        if (pending.size() > PENDING_MAX) {
            log.warn("控制应答等待位超过 {} 条，整体清理（设备长期不答）", PENDING_MAX);
            pending.clear();
        }
        CompletableFuture<Ack> future = new CompletableFuture<>();
        pending.put(key(channelId, sn), future);
        return future;
    }

    /**
     * 收到 {@code <Response><CmdType>DeviceControl</CmdType>} 时调用。
     *
     * <p>通道与 SN 必须都对上才算本次应答：同通道的连续操作会各自有 SN，
     * 只看通道会把上一次的应答填给这一次。
     */
    public void onResponse(String channelId, int sn, String result) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        Ack ack = new Ack(sn, channelId, result, System.currentTimeMillis());
        recent.put(key(channelId, sn), ack);
        CompletableFuture<Ack> future = pending.remove(key(channelId, sn));
        if (future != null) {
            future.complete(ack);
        } else {
            // 没登记等待位（如平台重启后设备补发，或命令由级联转发而来）：
            // 留在 recent 里即可，不算异常
            log.debug("收到无等待位的控制应答: channel={} sn={} result={}",
                    channelId, sn, result);
        }
    }

    /**
     * 等设备应答。
     *
     * @return 设备应答；超时或未答返回 {@code null}
     */
    public Ack await(String channelId, int sn, CompletableFuture<Ack> future, int timeoutMillis) {
        try {
            return future.get(Math.max(200, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 超时是最常见的情况（设备对云台类命令普遍不做应用层应答），不是异常
            return null;
        } finally {
            pending.remove(key(channelId, sn));
        }
    }

    /** 查最近一次该通道的应答（诊断用）。 */
    public Ack lastOf(String channelId, int sn) {
        return recent.get(key(channelId, sn));
    }
}
