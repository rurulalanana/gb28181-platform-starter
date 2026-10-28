package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.dahua.DahuaDeviceAccessProvider;
import io.github.rurulalanana.dahua.DahuaRegisterListener;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.dahua.internal.DahuaNativeLibrary;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.RegisterListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大华 SDK 直连的装配。
 *
 * <h3>为什么这里能引用大华的类而不破坏「国标链路可用」</h3>
 * 本类只<b>注册 bean 定义</b>，不实例化。Spring 的 {@code @Bean} 方法是懒的：
 * 只有真正有人注入 {@code DahuaDeviceAccessProvider} 时才会执行方法体。
 * 而平台侧唯一取用它的地方是 {@code DeviceAccessRegistry}，那里用的是
 * {@link ObjectProvider} 延迟解析 —— 所以「SDK 装没装好」不影响启动。
 *
 * <p>这个设计不是多余的：本项目实测过「大华 SDK 缺失导致整个平台起不来，
 * 连国标链路一起废掉」。根因就是 {@code NetSDKLib} 的静态块会
 * {@code Native.load} 原生库，一旦在容器启动期被触发就无法挽回。
 *
 * <h3>原生库缺失时的行为</h3>
 * provider 仍然被注册（否则 {@code DeviceAccessRegistry} 无法区分
 * 「不支持该类型」和「支持但当前不可用」），但每个方法都会快速失败，
 * 并在错误信息里说明缺什么 —— 见 {@link DahuaNativeLibrary#diagnose()}。
 *
 * <h3>SERVER 模式（设备主动注册）</h3>
 * 由 {@code dahua.server-mode.enabled=true} 开启。
 * 默认关闭：它要占用一个端口，且必须在设备侧配置「主动注册」才有意义，
 * 不能默认打开。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DahuaProperties.class)
public class DahuaConfiguration {

    /**
     * 接入实现。
     *
     * <p>{@code rtspPort} 与 {@code channelTotal} 取自配置：前者影响生成的
     * 播放地址，后者影响子码流的通道号推算（大华把子码流排在主码流之后）。
     */
    @Bean
    public DahuaDeviceAccessProvider dahuaDeviceAccessProvider(DahuaProperties props) {
        log.info("装配大华接入实现: enabled={} rtspPort={} channelTotal={}",
                props.isEnabled(), props.getRtspPort(), props.getChannelTotal());
        return new DahuaDeviceAccessProvider(props.getRtspPort(), props.getChannelTotal());
    }

    /**
     * 设备主动注册表（SERVER 模式的会话地址表）。
     *
     * <p>只在开启 SERVER 模式时创建 —— 它的唯一用途是记录「设备连上来的地址」，
     * CLIENT 模式下没有意义。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaRegisterTable dahuaRegisterTable(DahuaProperties props) {
        long expireMs = props.getServerMode().getExpireSeconds() * 1000L;
        log.info("大华主动注册表: 失效阈值 {} 秒", expireMs / 1000);
        return new DahuaRegisterTable(expireMs);
    }

    /**
     * 主动注册监听器。
     *
     * <p>由 {@link DahuaServerModeStarter} 在容器就绪后启动，
     * 而不是在这里 {@code start()} —— 启动失败（端口占用）要能被感知并上报，
     * 在 {@code @Bean} 方法里抛异常会让整个应用起不来，
     * 而「大华监听起不来」不应该拖垮国标链路。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public RegisterListener dahuaRegisterListener(DahuaRegisterTable table) {
        return new DahuaRegisterListener(table);
    }

    /**
     * 应用就绪后启动主动注册监听。
     *
     * <p>失败只记 ERROR 日志、不阻断启动 —— 端口被占用是部署期最常见的错误，
     * 但它的影响范围只有「大华设备注册不上」，不该让整个平台不可用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "dahua.server-mode", name = "enabled", havingValue = "true")
    public DahuaServerModeStarter dahuaServerModeStarter(RegisterListener listener,
                                                        DahuaProperties props) {
        return new DahuaServerModeStarter(listener, props);
    }

    /**
     * 关闭钩子：释放大华 SDK。
     *
     * <p>用独立的 bean 而不是 {@code @PreDestroy} 挂在 provider 上：
     * provider 可能是懒加载的，从没被创建过时不会有销毁回调，
     * 但 SDK 一旦初始化过就必须 {@code CLIENT_Cleanup}。
     */
    @Bean
    public DahuaShutdownHook dahuaShutdownHook() {
        return new DahuaShutdownHook();
    }

    /** 启动主动注册监听。 */
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
