package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.entity.GbDevice;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SDK 直连设备（大华等）的报警订阅桥。
 *
 * <h3>为什么需要"桥"而不是在回调里直接落库</h3>
 * 报警回调跑在 SDK 的<b>原生线程</b>上，同一线程还要处理后续事件。
 * 在里面写数据库（一次几毫秒到几十毫秒，遇到锁竞争更久）会阻塞整条回调链 ——
 * 现场表现是「报警只收到前几条，后面全丢」，而日志里什么都看不出来。
 *
 * <p>所以这里做了三段式：
 * <pre>
 *   SDK 原生线程  →  有界队列（不阻塞，满了丢最旧 + 计数）
 *                 →  业务线程（本类自己的单线程）  →  AlarmService 落库
 * </pre>
 *
 * <h3>为什么需要定时巡检重订阅</h3>
 * 订阅是<b>按登录句柄</b>的。设备重启、网络闪断后旧句柄作废，SDK 会自动重登拿到
 * 新句柄 —— 但新句柄<b>不在订阅列表里</b>，报警从此静默。定时巡检把
 * "有会话但没订阅"的设备补订上，这是这个类是 {@code ApplicationRunner}
 * 而不是一次性订阅的关键原因。
 */
@Slf4j
@Component
public class DahuaAlarmBridge implements ApplicationRunner {

    /** 队列容量：报警风暴（如动检连发）时宁可丢最旧的，也不能让原生线程阻塞。 */
    private static final int QUEUE_CAP = 4096;

    /** 巡检间隔：60 秒。设备重连后最多一分钟恢复订阅，比"永久静默"好得多。 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    private final DeviceService deviceService;
    private final AlarmService alarmService;
    private final DahuaCapabilityService dahuaCapability;

    private final LinkedBlockingQueue<DahuaExtAccess.AlarmEvent> queue =
            new LinkedBlockingQueue<>(QUEUE_CAP);

    /** 已成功订阅的会话键（{@code host:port}）；句柄变化后会重新订阅。 */
    private final Map<String, Long> subscribed = new ConcurrentHashMap<>();

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    /** 被识别为「非报警」（云台位置 / 订阅完成等状态回调）而丢弃的条数。 */
    private final AtomicLong ignored = new AtomicLong();

    private volatile boolean running = true;
    private Thread consumer;
    private Thread scanner;

    public DahuaAlarmBridge(DeviceService deviceService,
                            AlarmService alarmService,
                            DahuaCapabilityService dahuaCapability) {
        this.deviceService = deviceService;
        this.alarmService = alarmService;
        this.dahuaCapability = dahuaCapability;
    }

    @Override
    public void run(ApplicationArguments args) {
        consumer = new Thread(this::consumeLoop, "dahua-alarm-consume");
        consumer.setDaemon(true);
        consumer.start();

        scanner = new Thread(this::scanLoop, "dahua-alarm-subscribe");
        scanner.setDaemon(true);
        scanner.start();

        log.info("大华报警接入已启动（队列 {}，每 {} 秒补订阅一次）",
                QUEUE_CAP, SCAN_INTERVAL_MS / 1000);
    }

    /**
     * 收到一条 SDK 报警。
     *
     * <p>本方法就在原生线程上执行，<b>只能做入队</b>。
     * 队列满时丢最旧的一条并计数：宁可丢，也不能让设备侧的回调线程卡住。
     */
    private void onAlarm(DahuaExtAccess.AlarmEvent event) {
        received.incrementAndGet();
        if (!queue.offer(event)) {
            queue.poll();
            dropped.incrementAndGet();
            if (!queue.offer(event)) {
                return;
            }
        }
    }

    private void consumeLoop() {
        while (running) {
            try {
                DahuaExtAccess.AlarmEvent e = queue.poll(500, TimeUnit.MILLISECONDS);
                if (e == null) {
                    continue;
                }
                // 事件只带 IP，反查是哪台设备。反查不到就记一条 DEBUG：
                // 未纳管设备也在发报警是正常现象（发现过但还没添加）
                String deviceId = deviceIdByIp(e.deviceIp());
                if (deviceId == null) {
                    log.debug("收到未纳管设备的大华报警: ip={} cmd=0x{}",
                            e.deviceIp(), Integer.toHexString(e.command()));
                    continue;
                }
                // 事件明细：bytes / state / head 是判断「设备到底报的是触发还是恢复」的唯一证据。
                // 已收录的事件码用 DEBUG（生产不刷屏）；未收录的用 INFO ——
                // 这些码在新版 SDK 里可能仍无常量定义，只能靠 head 的字段布局反推语义。
                if (io.github.rurulalanana.dahua.ext.DahuaAlarmCodes.known(e.command())) {
                    log.debug("大华事件明细: device={} cmd=0x{} bytes={} state={} head={}",
                            deviceId, Integer.toHexString(e.command()), e.rawBytes(), e.state(), e.head());
                } else {
                    // 未收录的事件码：额外把结构体里的可打印字符抠出来。
                    // 这些码在 SDK 3.061 的 Java 常量里根本没有定义，字段布局是唯一线索，
                    // 而字符串字段（用户名 / IP / 路径）通常就藏在可打印区。
                    log.info("大华未收录事件: device={} cmd=0x{} bytes={} state={} ascii=\"{}\" head={}",
                            deviceId, Integer.toHexString(e.command()), e.rawBytes(), e.state(),
                            printable(e.head()), e.head());
                }
                var stored0 = alarmService.onDahuaAlarm(deviceId, e.command(), e.deviceIp(), e.state());
                // null = 被识别为「非报警」（云台位置 / 订阅完成），不算落库
                if (stored0 != null) {
                    stored.incrementAndGet();
                } else {
                    ignored.incrementAndGet();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                // 单条失败不能打死消费循环
                log.warn("处理大华报警失败: {}", ex.toString());
            }
        }
    }

    private void scanLoop() {
        while (running) {
            try {
                subscribePending();
            } catch (Exception e) {
                log.debug("大华报警补订阅异常: {}", e.toString());
            }
            try {
                Thread.sleep(SCAN_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 把所有"已登录但未订阅"的大华设备补订上。 */
    private void subscribePending() {
        List<GbDevice> devices;
        try {
            devices = deviceService.findBySource(DeviceKind.DAHUA.code());
        } catch (Exception e) {
            return;
        }
        for (GbDevice d : devices) {
            if (d.getRemoteIp() == null || d.getRemotePort() == null) {
                continue;
            }
            var opt = dahuaCapability.session(d.getDeviceId());
            if (opt.isEmpty()) {
                continue;
            }
            DahuaSdk.Session session = opt.get();
            String key = session.host() + ":" + session.port();
            // 句柄没变且已订阅过就跳过；句柄变了（设备重登）要重新订阅
            Long subscribedHandle = subscribed.get(key);
            if (subscribedHandle != null && subscribedHandle == session.handle().longValue()) {
                continue;
            }
            try {
                dahuaCapability.ext().subscribeAlarm(session, this::onAlarm);
                subscribed.put(key, session.handle().longValue());
                log.info("大华报警订阅成功: {} @ {}", d.getDeviceId(), key);
            } catch (Exception e) {
                log.debug("大华报警订阅失败: {} {} {}", d.getDeviceId(), key, e.toString());
            }
        }
    }

    /**
     * 从十六进制串里抠出可打印的 ASCII（长度 ≥4 的连续片段）。
     *
     * <p>只用于<b>识别未收录的事件码</b>：大华的报警结构体里常带用户名、IP、文件路径，
     * 这些是反推语义最直接的证据。正常报警不打这个。
     */
    private static String printable(String hex) {
        if (hex == null || hex.length() < 8) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            int v = Integer.parseInt(hex.substring(i, i + 2), 16);
            if (v >= 0x20 && v < 0x7F) {
                run.append((char) v);
            } else {
                if (run.length() >= 4) {
                    out.append(out.length() > 0 ? " | " : "").append(run);
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 4) {
            out.append(out.length() > 0 ? " | " : "").append(run);
        }
        return out.toString();
    }

    /** IP → 设备编号。同一 IP 多设备时取第一个（正常不会发生）。 */
    private String deviceIdByIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            for (GbDevice d : deviceService.findBySource(DeviceKind.DAHUA.code())) {
                if (ip.equals(d.getRemoteIp())) {
                    return d.getDeviceId();
                }
            }
        } catch (Exception e) {
            log.debug("按 IP 反查大华设备失败: {}", e.toString());
        }
        return null;
    }

    /** 订阅与队列统计（供诊断接口）。 */
    public Map<String, Object> stats() {
        return Map.of(
                "subscribedDevices", subscribed.size(),
                "received", received.get(),
                "stored", stored.get(),
                "ignored", ignored.get(),
                "dropped", dropped.get(),
                "queueSize", queue.size());
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (consumer != null) {
            consumer.interrupt();
        }
        if (scanner != null) {
            scanner.interrupt();
        }
    }
}
