package io.github.rurulalanana.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台配置（对应 application.yml 的 {@code gb28181.*}）。
 */
@Data
@ConfigurationProperties(prefix = "gb28181")
public class Gb28181Properties {

    /** 平台 20 位编码。 */
    private String platformId = "34020000002000000001";

    /** SIP 信令监听地址与端口。 */
    private String sipHost = "0.0.0.0";
    private int sipPort = 5060;

    /** 媒体 IP：SDP {@code c=} 行与播放地址主机，必须是设备可达地址；留空自动探测。 */
    private String mediaIp = "";

    /** 媒体服务端口。 */
    private int httpPort = 8081;
    private int rtmpPort = 11935;
    private int rtspPort = 18554;
    private int rtcPort = 18000;

    /** ZLM 应用名（播放 URL 第一段）。 */
    private String app = "rtp";

    /** 是否随流上下线自动录像。 */
    private boolean autoRecord = false;

    /** 录像根目录（留空用 ${user.dir}/record）。 */
    private String recordRoot = "";

    /** 设备模拟器是否随应用启动。 */
    private Simulator simulator = new Simulator();

    /** 内置模拟设备配置。 */
    @Data
    public static class Simulator {
        /** 是否启用。 */
        private boolean enabled = true;
        /** 模拟设备编码前缀（依次生成 <prefix>01、<prefix>02…）。 */
        private String deviceIdPrefix = "34020000001320000001";
        /** 启动时注册的模拟设备数量。 */
        private int count = 1;
        /** 是否为模拟设备自动用 ffmpeg 推测试视频流（需本机有 ffmpeg）。 */
        private boolean pushTestMedia = false;
        /** ffmpeg 可执行文件路径。 */
        private String ffmpeg = "ffmpeg";
        /** 测试画面参数（testsrc2 滤镜）。 */
        private String testSource = "testsrc2=size=640x360:rate=25";
    }
}