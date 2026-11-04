package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.dahua.DahuaDeviceAccessProvider;
import io.github.rurulalanana.dahua.DahuaRegisterListener;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import org.springframework.context.annotation.Lazy;
import io.github.rurulalanana.deviceaccess.RegisterListener;
import io.github.rurulalanana.starter.service.DeviceProvisionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大华 SDK 直连的装配。本类只<b>注册 bean 定义</b>、不实例化：{@code @Bean} 方法是懒的，
 * 只有有人注入 {@code DahuaDeviceAccessProvider} 时才执行方法体，而平台侧唯一取用处
 * {@code DeviceAccessRegistry} 用 {@link ObjectProvider} 延迟解析 —— 所以 SDK 装没装好不影响启动。
 *
 * <p>实测过「大华 SDK 缺失导致整个平台起不来，连国标链路一起废掉」：根因是 {@code NetSDKLib}
 * 静态块会 {@code Native.load} 原生库，容器启动期一旦触发就无法挽回。
 *
 * <p>原生库缺失时 provider 仍被注册（否则无法区分「不支持该类型」与「支持但当前不可用」），
 * 但每个方法快速失败并说明缺什么，见 {@link DahuaNativeLibrary#diagnose()}。
 *
 * <p>SERVER 模式（设备主动注册）默认关闭：它要占用一个端口，且必须在设备侧配置才有意义。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DahuaProperties.class)
public class DahuaConfiguration {

    /**
     * 接入实现。{@code rtspPort} 影响播放地址，{@code channelTotal} 影响子码流通道号推算
     * （大华把子码流排在主码流之后）。
     */
    @Bean
    public DahuaDeviceAccessProvider dahuaDeviceAccessProvider(DahuaProperties props) {
        log.info("装配大华接入实现: enabled={} rtspPort={} channelTotal={}",
                props.isEnabled(), props.getRtspPort(), props.getChannelTotal());
        return new DahuaDeviceAccessProvider(props.getRtspPort(), props.getChannelTotal());
    }

    /** 设备主动注册表（SERVER 模式的会话地址表），只在开启 SERVER 模式时创建。 */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaRegisterTable dahuaRegisterTable(DahuaProperties props) {
        long expireMs = props.getServerMode().getExpireSeconds() * 1000L;
        log.info("大华主动注册表: 失效阈值 {} 秒", expireMs / 1000);
        return new DahuaRegisterTable(expireMs);
    }

    /**
     * 主动注册监听器。由 {@link DahuaServerModeStarter} 在容器就绪后启动：启动失败（端口占用）
     * 要能被感知上报，在 {@code @Bean} 方法里抛异常会让整个应用起不来。
     *
     * <p>注册成功后的登录由 {@link #serverLoginHandler} 负责 —— 少了这一步，
     * 连接会一直停在「待验证」状态，设备每 60 秒重连一次（见 {@link DahuaRegisterListener} 类注释）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public RegisterListener dahuaRegisterListener(DahuaRegisterTable table, DahuaProperties props) {
        return new DahuaRegisterListener(table, serverLoginHandler(props));
    }

    /**
     * 「注册即登录」：设备注册上来的<b>那条连接</b>上按 {@code emSpecCap=2 + SN} 登录。
     *
     * <h3>为什么必须登录</h3>
     * 大华的主动注册只把 TCP 连接交给你，真正的「确认」是平台在这条连接上登录。
     * 不登录时连接停在待验证状态，<b>实测 60 秒后被丢弃、设备换源端口重新注册</b>，
     * 永远循环。官方 demo 与《Auto Register User's Manual》§3.2.4 都是这个写法。
     *
     * <h3>为什么用回调里的端口而不是 {@code sdk-port}</h3>
     * {@code sdk-port}（默认 37777）是设备的服务端口，用它能连通，但那是<b>另一条直连连接</b>：
     * 注册连接依旧没人登录，照样 60 秒断一次。要让连接从「待验证」变成会话，
     * 必须登录回调给的那个源端口。
     *
     * <h3>为什么可能返回 null</h3>
     * 没配账号密码时不能瞎试：大华账号连续登录失败会被锁。这种情况返回 {@code null}，
     * 行为退回「只记账」，设备重连频率升高但功能不受影响。
     */
    private static DahuaRegisterListener.Handler serverLoginHandler(DahuaProperties props) {
        DahuaProperties.ServerMode sm = props.getServerMode();
        if (!sm.isAutoProvision()) {
            return null;
        }
        String user = sm.getDefaultUsername();
        String password = sm.getDefaultPassword();
        if (password == null || password.isBlank()) {
            return null;
        }
        return (serial, ip, port) -> {
            DahuaSdk.Session session = DahuaSdk.get().session(ip, port, user, password,
                    io.github.rurulalanana.deviceaccess.ConnectMode.SERVER, serial);
            if (session == null) {
                // 已由 DahuaSdk 打出具体原因（错误码 / 账号密码提示），这里只补一句上下文
                log.warn("主动注册登录失败: sn={} {}:{} —— 连接会停在待验证状态，"
                        + "设备约 60 秒后重连一次", serial, ip, port);
            } else {
                log.info("主动注册会话已建立: sn={} {}:{} 通道数={}",
                        serial, ip, port, session.channelCount());
            }
        };
    }

    /**
     * 应用就绪后启动主动注册监听。失败只记 ERROR、不阻断启动：端口占用的影响范围只有
     * 「大华设备注册不上」，不该让整个平台不可用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaServerModeStarter dahuaServerModeStarter(RegisterListener listener,
                                                        DahuaProperties props) {
        return new DahuaServerModeStarter(listener, props);
    }

    /**
     * 注册即纳管：把注册表里的设备自动建成 {@code gb_device} 档案。
     *
     * <p>与监听器分开两个 bean 的原因：监听器只负责「设备能连上」，
     * 纳管负责「平台认识它」。两者的失败面完全不同（端口 vs 账号/网络），
     * 合在一起会让排障时分不清是注册没到还是建档案失败。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaAutoProvisionRunner dahuaAutoProvisionRunner(DahuaRegisterTable table,
                                                             DeviceProvisionService provisionService,
                                                             io.github.rurulalanana.starter.service.DeviceService deviceService,
                                                             DahuaProperties props) {
        return new DahuaAutoProvisionRunner(table, provisionService, deviceService, props);
    }

    /**
     * SDk 扩展能力（发现 / 抓图 / 配置 / 预置位 / 巡航 / 报警 / 下载 / 对讲）。
     *
     * <p><b>为什么用 {@code @Lazy}</b>：本类构造时不加载原生库，但它被
     * {@code DahuaCapabilityService} 注入后，第一次调方法就会触发
     * {@code DahuaSdk} 的静态初始化（{@code Native.load}）。标 {@code @Lazy}
     * 让「没装 SDK 的部署」在启动阶段完全不受影响，与
     * {@code DeviceAccessRegistry} 的延迟解析策略一致。
     */
    @Bean
    @Lazy
    public io.github.rurulalanana.dahua.ext.DahuaExtAccess dahuaExtAccess(DahuaProperties props) {
        log.debug("装配大华扩展能力（enabled={}）", props.isEnabled());
        return new io.github.rurulalanana.dahua.ext.DahuaExtAccess();
    }

    /**
     * 关闭钩子：释放大华 SDK。用独立 bean 而非 provider 上的 {@code @PreDestroy}：
     * provider 可能从未被创建过，但 SDK 一旦初始化过就必须 {@code CLIENT_Cleanup}。
     */
    @Bean
    public DahuaShutdownHook dahuaShutdownHook() {
        return new DahuaShutdownHook();
    }

    public static class DahuaServerModeStarter implements org.springframework.boot.ApplicationRunner {

        private final RegisterListener listener;
        private final DahuaProperties props;

        DahuaServerModeStarter(RegisterListener listener, DahuaProperties props) {
            this.listener = listener;
            this.props = props;
        }

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            if (!DahuaNativeLibrary.available()) {
                log.warn("大华主动注册监听未启动：{}", DahuaNativeLibrary.diagnose());
                return;
            }
            DahuaProperties.ServerMode cfg = props.getServerMode();
            try {
                listener.start(cfg.getBindIp(), cfg.getPort());
                log.info("大华主动注册监听已启动 {}:{}，请在设备侧「网络 → 主动注册」中填写本平台地址",
                        cfg.getBindIp(), cfg.getPort());
            } catch (RuntimeException e) {
                // 端口占用等部署期错误：只影响大华接入，不阻断平台启动
                log.error("大华主动注册监听启动失败（仅影响大华设备主动注册）: {}", e.getMessage());
            }
        }
    }

    /** 进程退出时释放 SDK 与全部登录会话。 */
    public static class DahuaShutdownHook {

        @jakarta.annotation.PreDestroy
        public void destroy() {
            try {
                DahuaSdk.get().shutdown();
            } catch (Throwable t) {
                log.warn("释放大华 SDK 异常: {}", t.toString());
            }
        }
    }
}
