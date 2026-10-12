package io.github.rurulalanana.demo;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.MediaServerProperties;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.demo.simulator.SimulatedDevice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：在 JVM 内跑通「模拟设备注册 → 平台点播 → 收流会话 → 播放地址」全链路，
 * 并验证 REST 层可用。
 *
 * <p>用随机端口避免与其他实例冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gb28181.sip-port=15070",
                "gb28181.media-ip=127.0.0.1",
                "gb28181.http-port=0",
                "gb28181.rtmp-port=0",
                "gb28181.rtsp-port=0",
                "gb28181.rtc-port=0",
                "gb28181.simulator.enabled=true",
                "gb28181.simulator.count=1",
                "gb28181.simulator.push-test-media=true"
        })
class Gb28181PlatformIntegrationTest {

    @LocalServerPort
    int httpPort;

    @Autowired
    private Gb28181MediaPlatform platform;

    @Autowired
    private TestRestTemplate rest;

    private static final String DEVICE = "34020000001320000001";

    @Test
    void 平台与模拟设备应就绪() {
        assertNotNull(platform, "平台 Bean 应存在");
        assertTrue(platform.sipServer().isRunning(), "SIP 服务应运行中");
        assertTrue(platform.mediaServer().isStarted(), "媒体服务应运行中");
        assertTrue(platform.mediaServer().getHttpPort() > 0, "媒体 HTTP 端口应生效");

        // 模拟设备已 REGISTER，平台注册表应有它
        var session = platform.sipServer().getRegistry().get(DEVICE);
        assertNotNull(session, "模拟设备应已注册到平台：" + DEVICE);
        assertTrue(platform.sipServer().getRegistry().isOnline(DEVICE));
    }

    @Test
    void 平台点播应建立收流会话() {
        // 平台主动点播：发 INVITE，模拟设备回 200 OK
        RtpSession session = platform.mediaService().play(DEVICE);
        assertNotNull(session);
        assertEquals(DEVICE, session.getChannelId());
        assertTrue(session.getPort() > 0, "应分配到 RTP 收流端口");
        assertNotNull(session.getSsrc(), "应生成平台 SSRC");
        assertEquals(io.github.rurulalanana.gb28181.sip.zlm.MediaType.REALTIME, session.getType());

        // 播放地址齐全（WebRTC 为嵌入式模式浏览器通路）
        var urls = platform.mediaService().playUrls(DEVICE);
        assertNotNull(urls, "应有播放地址");
        assertNotNull(urls.getWebrtc());
        assertTrue(urls.getRtsp().contains("/" + DEVICE));

        // 清理，避免影响其它用例
        platform.mediaService().stop(DEVICE);
    }

    @Test
    void rest接口应可用() {
        ResponseEntity<Map> status = rest.getForEntity("http://127.0.0.1:" + httpPort + "/api/status", Map.class);
        assertEquals(200, status.getStatusCode().value());
        assertEquals("34020000002000000001", status.getBody().get("platformId"));

        ResponseEntity<List> simulators = rest.getForEntity("http://127.0.0.1:" + httpPort + "/api/simulators", List.class);
        assertEquals(200, simulators.getStatusCode().value());
        assertFalse(simulators.getBody().isEmpty(), "应列出模拟设备");

        ResponseEntity<List> devices = rest.getForEntity("http://127.0.0.1:" + httpPort + "/api/devices", List.class);
        assertEquals(200, devices.getStatusCode().value());
        assertFalse(devices.getBody().isEmpty(), "平台注册表应有在线设备");
    }

    private static boolean exists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String ffprobeRaw(String url) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error", "-rtsp_transport", "tcp",
                    "-show_entries", "stream=codec_name", "-of", "csv=p=0",
                    "-timeout", "3000000", url).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void 录像开关与索引应可用() {
        var recording = platform.recordingService().startRecording("rec-test-chan");
        assertNotNull(recording);
        assertTrue(platform.recordingService().isRecording("rec-test-chan"));
        assertEquals(1, platform.recordingService().recordings("rec-test-chan").size());

        var closed = platform.recordingService().stopRecording("rec-test-chan");
        assertNotNull(closed);
        assertEquals(io.github.rurulalanana.gb28181.sip.zlm.Recording.CLOSED, closed.getStatus());
        assertFalse(platform.recordingService().isRecording("rec-test-chan"));
    }
}
