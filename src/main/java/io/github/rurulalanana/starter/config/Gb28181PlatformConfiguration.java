package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.gb28181.sip.security.CredentialProvider;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.MediaServerProperties;
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
                                                     ObjectProvider<RuntimeConfigService> configProvider) {
        MediaServerProperties.MediaServerPropertiesBuilder builder = MediaServerProperties.builder()
                .platformId(props.getPlatformId())
                .sipHost(props.getSipHost())
                .sipAdvertisedIp(props.getSipAdvertisedIp())
                .sipPort(props.getSipPort())
                /*
                 * 用 CredentialProvider 而不是 sipPassword：
                 * sipPassword 在构造时被读成常量，之后改配置必须重启；
                 * CredentialProvider 是<b>每次 REGISTER 校验时</b>调用的函数，
                 * 因此可以读运行时配置实现「改密码立即生效」。
                 *
                 * 用 ObjectProvider 延迟取：本 bean 构造得很早（信令栈要立刻起），
                 * 直接注入 RuntimeConfigService 会连带提前初始化数据源。
                 */
                .credentialProvider(runtimeSipPassword(configProvider, props))
                .sipTransports(props.getSipTransports())
                .httpPort(props.getHttpPort())
                .rtmpPort(props.getRtmpPort())
                .rtspPort(props.getRtspPort())
                .rtcPort(props.getRtcPort())
                .app(props.getApp())
                .rtpSinglePort(props.isRtpSinglePort())
                .rtpSinglePortNumber(props.getRtpSinglePortNumber())
                .autoRecord(props.isAutoRecord())
                .recordMaxSecond(props.getRecordMaxSecond())
                .streamNoneReaderDelayMs(props.getStreamNoneReaderDelayMs())
                .recordRoot(props.resolvedRecordRoot());
        // 注意：Lombok @Builder.Default 只在"未显式赋值"时生效，
        // 这里配置为空时不能传 null，否则 mediaIp 会变成 null（SDP 里写成 IN IP4 null）
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
     * SIP 注册鉴权：每次校验时读运行时配置。
     *
     * <p><b>为什么不用 {@code sipPassword} 直接传常量</b>：那样只能在启动时确定，
     * 改密码必须重启（会中断所有录像与注册）。{@code credentialProvider}
     * 是 {@code GbSipListener} 在<b>每次收到 REGISTER</b> 时调用的函数，
     * 因此改成读 {@link RuntimeConfigService} 就能「保存后立即生效」。
     *
     * <p><b>为什么必须实现 {@code isEnabled()}</b>：密码留空 = 不鉴权，
     * 而「不鉴权」不能靠 {@code passwordOf} 返回 {@code null} 表达
     * （那个语义是「用户不存在 → 403」，清空密码会把所有设备挡在门外）。
     * 参考站也有「设备统一接入密码」这一项，运维会期望它可清空。
     *
     * @param configProvider 延迟取配置服务（避免提前初始化数据源）
     * @param props          启动配置，仅在配置服务不可用时兜底
     * @return 凭据提供者
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

    /**
     * 取当前生效的接入密码。
     *
     * @param configProvider 配置服务
     * @param props          启动配置（配置服务未就绪时兜底）
     * @return 密码；可能为空串
     */
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
}
