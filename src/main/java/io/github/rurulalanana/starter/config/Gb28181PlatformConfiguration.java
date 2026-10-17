package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.MediaServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public Gb28181MediaPlatform gb28181MediaPlatform(Gb28181Properties props) {
        MediaServerProperties.MediaServerPropertiesBuilder builder = MediaServerProperties.builder()
                .platformId(props.getPlatformId())
                .sipHost(props.getSipHost())
                .sipAdvertisedIp(props.getSipAdvertisedIp())
                .sipPort(props.getSipPort())
                .sipPassword(props.getSipPassword())
                .httpPort(props.getHttpPort())
                .rtmpPort(props.getRtmpPort())
                .rtspPort(props.getRtspPort())
                .rtcPort(props.getRtcPort())
                .app(props.getApp())
                .rtpSinglePort(props.isRtpSinglePort())
                .rtpSinglePortNumber(props.getRtpSinglePortNumber())
                .autoRecord(props.isAutoRecord())
                .streamNoneReaderDelayMs(props.getStreamNoneReaderDelayMs())
                .recordRoot(isBlank(props.getRecordRoot())
                        ? System.getProperty("user.dir") + "/record" : props.getRecordRoot());
        // 注意：Lombok @Builder.Default 只在"未显式赋值"时生效，
        // 这里配置为空时不能传 null，否则 mediaIp 会变成 null（SDP 里写成 IN IP4 null）
        if (!isBlank(props.getMediaIp())) {
            builder.mediaIp(props.getMediaIp());
        }
        if (!isBlank(props.getRtcExternIp())) {
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
