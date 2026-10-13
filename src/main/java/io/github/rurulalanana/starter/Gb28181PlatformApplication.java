package io.github.rurulalanana.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.rurulalanana.starter.config.Gb28181Properties;

/**
 * GB28181 流媒体平台测试工程。
 *
 * <p>启动后：
 * <ul>
 *   <li>SIP 信令服务（gb28181-sip）监听 5060/UDP，等设备注册</li>
 *   <li>媒体服务（gb28181-sip-zlm 内嵌 ZLMediaKit）监听 HTTP/RTMP/RTSP/WebRTC</li>
 *   <li>内置设备模拟器，可用 ffmpeg 造真实视频流，无需真实摄像头即可验证媒体链路</li>
 *   <li>测试页面：<a href="http://localhost:8080/">localhost:8080</a></li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(Gb28181Properties.class)
public class Gb28181PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(Gb28181PlatformApplication.class, args);
    }
}