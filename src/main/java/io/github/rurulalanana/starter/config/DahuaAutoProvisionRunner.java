package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import io.github.rurulalanana.starter.service.DeviceProvisionService;
import io.github.rurulalanana.starter.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「主动注册」变成「自动纳管」：注册表里的设备会自动建成 {@code gb_device} 档案。
 *
 * <h3>为什么需要它</h3>
 * 主动注册只解决「设备能连上平台」，不解决「平台知道有这台设备」。改造前：
 * 注册回调只写内存注册表（{@link DeviceRegisterTable}），设备表里什么都没有，
 * 用户看着空空的设备页会以为接入失败。设备主动注册的全部价值就是
 * <b>不用人工录 IP</b>，如果注册完还要手填一遍，这个模式等于没做。
 *
 * <h3>为什么不直接在 SDK 回调线程里写库</h3>
 * 回调跑在原生线程上，那里禁止碰数据库：一次慢查询会阻塞后续所有设备的心跳，
 * 表现是「设备集体掉线」。所以这里用<b>轮询注册表</b>的方式解耦 ——
 * 回调只写内存（微秒级），落库由独立线程做。
 *
 * <h3>通道目录也一起拉</h3>
 * SDK 设备不会像国标那样主动上报目录，必须平台去问。建完档案立刻调
 * {@code refreshCatalog}：它同时会把 {@code online} 按探测结果写好，
 * 于是设备页一出现就是「在线 + N 个通道」，而不是一条连不上的空记录。
 */
public class DahuaAutoProvisionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DahuaAutoProvisionRunner.class);

    /** 扫描周期。注册回调是亚秒级的，5 秒足够及时，又不会空转浪费 CPU。 */
    private static final long TICK_MS = 5_000L;

    /** 成功纳管后的冷却时间：设备每次重连都会刷新注册记录，不能每次都重建档案。 */
    private static final long SUCCESS_COOLDOWN_MS = 10 * 60_000L;

    /** 失败后的重试间隔：设备可能刚上电、服务还没起，60 秒后再试。 */
    private static final long FAILURE_RETRY_MS = 60_000L;

    private final DeviceRegisterTable table;
    private final DeviceProvisionService provisionService;
    private final DeviceService deviceService;
    private final DahuaProperties props;

    /** 每台设备的下次尝试时间（毫秒）。 */
    private final Map<String, Long> nextAttemptAt = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    public DahuaAutoProvisionRunner(DeviceRegisterTable table,
                                    DeviceProvisionService provisionService,
                                    DeviceService deviceService,
                                    DahuaProperties props) {
        this.table = table;
        this.provisionService = provisionService;
        this.deviceService = deviceService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        DahuaProperties.ServerMode sm = props.getServerMode();
        if (!sm.isAutoProvision()) {
            log.info("大华自动接入未开启（dahua.server-mode.auto-provision=false），注册设备只进注册表");
            return;
        }
        if (sm.getDefaultPassword() == null || sm.getDefaultPassword().isBlank()) {
            // 不能静默跳过：用户会一直等设备出现在列表里
            log.warn("大华自动接入未生效：未配置设备密码。"
                    + "请设置环境变量 GB_DAHUA_PASSWORD，或启动参数 "
                    + "--dahua.server-mode.default-password=***");
            return;
        }
        Thread worker = new Thread(this::loop, "dahua-autoprovision");
        worker.setDaemon(true);
        worker.start();
        log.info("大华自动接入已启动：注册即建档案（账号 {}，SDK 端口 {}，每 {} 秒扫描）",
                sm.getDefaultUsername(), sm.getSdkPort(), TICK_MS / 1000);
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (Throwable t) {
                // 单轮失败不能打死循环，否则一次数据库抖动就永久停止自动接入
                log.warn("大华自动接入本轮扫描异常: {}", t.toString());
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 扫一遍注册表，把没纳管过的设备建档案。 */
    private void tick() {
        List<DeviceRegisterTable.RegisteredDevice> devices = table.list();
        if (devices.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        DahuaProperties.ServerMode sm = props.getServerMode();
        for (DeviceRegisterTable.RegisteredDevice dev : devices) {
            String sn = dev.serial();
            Long due = nextAttemptAt.get(sn);
            if (due != null && now < due) {
                continue;
            }
            /*
             * 已在设备表里的不再自动纳管。
             *
             * <p>这条是给「用户在界面上手填账号密码」让路：自动纳管用的是全局默认账号，
             * 如果每次都重建档案，用户填的那份凭据会被默认值覆盖，表现为「昨天还能连，今天密码错」。
             * 已纳管设备由 refreshCatalog 之外的巡检负责，不在这里动。
             */
            if (deviceService.findDevice(sn) != null) {
                nextAttemptAt.put(sn, now + SUCCESS_COOLDOWN_MS);
                continue;
            }
            try {
                /*
                 * 档案里存的是设备服务端口（sdk-port，默认 37777），不是注册回调里的源端口。
                 *
                 * 两个端口都能连通 —— 早期这里写的是「拿回调端口当连接目标必然失败」，
                 * 实测是错的（探针用回调端口登录成功）。真正的区别是用途：
                 *   - 回调端口只在本次注册有效，下一分钟就变了，不能进档案；
                 *   - 37777 是设备的常驻服务端口，设备换网后仍然有效，直连操作都靠它。
                 * 「把注册那条连接变成登录会话」是另一件事，由
                 * DahuaConfiguration.serverLoginHandler 在注册回调后立刻完成。
                 */
                provisionService.addDahua(sn, dev.ip(), sm.getSdkPort(),
                        sm.getDefaultUsername(), sm.getDefaultPassword(), null);
                int channels = provisionService.refreshCatalog(sn);
                nextAttemptAt.put(sn, now + SUCCESS_COOLDOWN_MS);
                log.info("大华设备自动接入成功: {} @ {}:{}，同步通道 {} 个",
                        sn, dev.ip(), sm.getSdkPort(), channels);
            } catch (Exception e) {
                nextAttemptAt.put(sn, now + FAILURE_RETRY_MS);
                log.warn("大华设备自动接入失败: {} @ {}:{}，{} 秒后重试。原因: {}",
                        sn, dev.ip(), sm.getSdkPort(), FAILURE_RETRY_MS / 1000, e.getMessage());
            }
        }
    }

    /** 停止轮询（容器关闭时由 Spring 调用）。 */
    public void shutdown() {
        running = false;
    }
}
