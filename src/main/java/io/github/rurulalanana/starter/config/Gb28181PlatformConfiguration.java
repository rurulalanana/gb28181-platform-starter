package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.gb28181.sip.security.CredentialProvider;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.MediaServerProperties;
import io.github.rurulalanana.starter.service.AccessControlService;
import io.github.rurulalanana.starter.service.CascadeParentRegistry;
import io.github.rurulalanana.starter.service.ConfigKeys;
import io.github.rurulalanana.starter.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 装配 GB28181 流媒体平台（信令 + 媒体）。
 *
 * <p>启动失败（如媒体端口被占用）会直接抛异常，避免"以为起来了其实没起"。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class Gb28181PlatformConfiguration {

    @Bean(destroyMethod = "stop")
    public Gb28181MediaPlatform gb28181MediaPlatform(Gb28181Properties props,
                                                     ObjectProvider<RuntimeConfigService> configProvider,
                                                     ObjectProvider<AccessControlService> aclProvider,
                                                     ObjectProvider<CascadeParentRegistry> cascadeParentProvider) {
        MediaServerProperties.MediaServerPropertiesBuilder builder = MediaServerProperties.builder()
                .platformId(props.getPlatformId())
                .sipHost(props.getSipHost())
                .sipAdvertisedIp(props.getSipAdvertisedIp())
                .sipPort(props.getSipPort())
                // 用 CredentialProvider 而不是 sipPassword：后者在构造时被读成常量，改密码必须重启；
                // provider 每次 REGISTER 校验时调用，可读运行时配置实现「改密码立即生效」。
                // ObjectProvider 延迟取：本 bean 构造得很早，直接注入会提前初始化数据源。
                .credentialProvider(runtimeSipPassword(configProvider, props))
                // 黑白名单同样每次 REGISTER 现取；拿不到就返回 null（不装配），
                // 行为与加黑白名单之前一致，不会因配置服务未就绪把所有设备挡在外面。
                .accessControl(aclProvider.getIfAvailable())
                // 上级平台判定器：媒体层靠它分辨「INVITE 来自我的上级」。不注入时级联上行点播会退化成
                // 「开接收会话等设备推流」，上级永远等不到数据。判据来自配置表，用无依赖的内存集合承载，
                // 避开「媒体平台 ↔ 链路服务」的 bean 循环。
                .uplinkPlatformChecker(lazyUplinkChecker(cascadeParentProvider))
                .sipTransports(props.getSipTransports())
                .httpPort(props.getHttpPort())
                .rtmpPort(props.getRtmpPort())
                .rtspPort(props.getRtspPort())
                .rtcPort(props.getRtcPort())
                .app(props.getApp())
                .rtpSinglePort(props.isRtpSinglePort())
                .rtpSinglePortNumber(props.getRtpSinglePortNumber())
                // RTP 收流缓冲：只在配置了正数时覆盖，避免 yml 写成 0 反而把缓冲清零。
                .udpRecvSocketBuffer(props.getUdpRecvSocketBuffer() > 0
                        ? props.getUdpRecvSocketBuffer() : 8 * 1024 * 1024)
                // 等轨就绪时间：ZLM 默认 10 秒，实测经下级平台转发的设备最慢 11 秒才推视频，
                // 超时会被永久丢弃 H264 轨、只剩音频（现象是「某些录像段怎么都播不出来」）。
                .waitTrackReadyMs(props.getWaitTrackReadyMs() > 0
                        ? props.getWaitTrackReadyMs() : 30_000)
                .autoRecord(props.isAutoRecord())
                .recordMaxSecond(props.getRecordMaxSecond())
                .streamNoneReaderDelayMs(props.getStreamNoneReaderDelayMs())
                .recordRoot(props.resolvedRecordRoot());
        // Lombok @Builder.Default 只在「未显式赋值」时生效：配置为空时不能传 null，
        // 否则 mediaIp 变 null，SDP 里会写成 IN IP4 null。
        if (StringUtils.hasText(props.getMediaIp())) {
            builder.mediaIp(props.getMediaIp());
        }
        if (StringUtils.hasText(props.getRtcExternIp())) {
            builder.rtcExternIp(props.getRtcExternIp());
        }
        MediaServerProperties mediaProps = builder.build();

        Gb28181MediaPlatform platform = new Gb28181MediaPlatform(mediaProps);
        platform.start();
        log.info("GB28181 平台已启动：SIP {}:{} / 媒体 http={} rtmp={} rtsp={} rtc={} / RTP收流={}",
                props.getSipHost(), props.getSipPort(),
                platform.mediaServer().getHttpPort(), platform.mediaServer().getRtmpPort(),
                platform.mediaServer().getRtspPort(), platform.mediaServer().getRtcPort(),
                platform.mediaServer().isSinglePortMode()
                        ? ("单端口:" + platform.mediaServer().getSinglePort()) : "每通道独占端口");
        return platform;
    }

    /**
     * SIP 注册鉴权：每次校验时读运行时配置。用常量的话改密码必须重启（中断所有录像与注册）。
     * 必须实现 {@code isEnabled()}：密码留空 = 不鉴权，而「不鉴权」不能靠 {@code passwordOf}
     * 返回 {@code null} 表达（那语义是「用户不存在 → 403」，会把所有设备挡在门外）。
     */
    private static CredentialProvider runtimeSipPassword(
            ObjectProvider<RuntimeConfigService> configProvider, Gb28181Properties props) {
        return new CredentialProvider() {
            @Override
            public String passwordOf(String username) {
                return currentPassword(configProvider, props);
            }

            @Override
            public boolean isEnabled() {
                // 空密码 = 不鉴权（与 sip-password 留空的原语义一致）
                return StringUtils.hasText(currentPassword(configProvider, props));
            }
        };
    }

    /** 取当前生效的接入密码；配置服务未就绪时回退 yml 值，绝不放空。 */
    private static String currentPassword(
            ObjectProvider<RuntimeConfigService> configProvider, Gb28181Properties props) {
        RuntimeConfigService cfg = configProvider.getIfAvailable();
        if (cfg == null) {
            // 极早期（信令栈刚起、配置服务还没就绪）：用 yml 值，绝不放空
            String fallback = props.getSipPassword();
            return fallback == null ? "" : fallback;
        }
        String password = cfg.getString(ConfigKeys.SIP_PASSWORD);
        return password == null ? "" : password;
    }

    /**
     * 上级平台判定器（延迟取 bean）。传 lambda 而非当时的实例：媒体平台构造得很早，
     * {@code CascadeParentRegistry} 可能还没创建。拿不到返回 {@code false}（当普通设备 INVITE 处理）。
     */
    private static java.util.function.Predicate<String> lazyUplinkChecker(
            ObjectProvider<CascadeParentRegistry> provider) {
        return platformId -> {
            if (platformId == null || platformId.isEmpty()) {
                return false;
            }
            CascadeParentRegistry registry = provider.getIfAvailable();
            return registry != null && registry.contains(platformId);
        };
    }
}
