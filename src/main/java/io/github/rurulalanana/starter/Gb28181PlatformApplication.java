package io.github.rurulalanana.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.rurulalanana.starter.config.Gb28181Properties;

/**
 * GB28181 流媒体平台测试工程。
 *
 * <p>启动后：
 * <ul>
 *   <li>SIP 信令服务（gb28181-sip）监听 5060/UDP，等设备注册</li>
 *   <li>媒体服务（gb28181-sip-zlm 内嵌 ZLMediaKit）监听 HTTP/RTMP/RTSP/WebRTC</li>
 *   <li>REST API 挂载在 {@code /api/v1/**}</li>
 *   <li>设备、通道、流、录像、报警等数据落 PostgreSQL（MyBatis-Plus）</li>
 *   <li>测试页面：<a href="http://localhost:8080/">localhost:8080</a></li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(Gb28181Properties.class)
public class Gb28181PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(Gb28181PlatformApplication.class, args);
    }
}