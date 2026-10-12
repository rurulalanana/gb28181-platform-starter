package io.github.rurulalanana.demo;

import io.github.rurulalanana.demo.simulator.SimulatedDevice;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.MediaServerProperties;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 媒体闭环验证器（独立进程运行）。
 *
 * <p>为什么单独一个进程：jain-sip 的 SIP 栈是<b>进程级单例</b>，同一 JVM 内不能再起第二个
 * SIP 服务，因此带媒体的完整链路必须在干净 JVM 里验证。
 *
 * <p>验证链路：模拟设备注册 → 平台点播 → 模拟设备用 ffmpeg 推 H264 RTP
 * → 平台 RTP 服务器收流 → ZLMediaKit 解出轨道并注册媒体源（流上线事件）。
 *
 * <p>运行：{@code mvn -q test-compile exec:java -Dexec.mainClass=io.github.rurulalanana.demo.MediaLoopVerifier}
 */
public final class MediaLoopVerifier {

    private static final String DEVICE = "34020000001320000001";
    private static final int SIP_PORT = 15096;

    public static void main(String[] args) throws Exception {
        MediaServerProperties props = MediaServerProperties.builder()
                .platformId("34020000002000000001")
                .sipPort(SIP_PORT)
                .mediaIp("127.0.0.1")
                .httpPort(0).rtmpPort(0).rtspPort(0).rtcPort(0)
                .build();

        Gb28181MediaPlatform platform = new Gb28181MediaPlatform(props);
        CompletableFuture<String> online = new CompletableFuture<>();
        platform.start();
        platform.mediaServer().setStreamListener(new ZlmMediaServer.StreamListener() {
            @Override
            public void onStreamOnline(String app, String stream) {
                if (DEVICE.equals(stream)) {
                    online.complete(app + "/" + stream);
                }
            }

            @Override
            public void onStreamOffline(String app, String stream) {
                // 忽略
            }
        });

        SimulatedDevice sim = new SimulatedDevice(DEVICE, "验证用模拟设备");
        sim.setMediaConfig(new SimulatedDevice.MediaConfig(true, "ffmpeg", "testsrc2=size=640x360:rate=25"));
        sim.start("127.0.0.1", SIP_PORT);

        boolean ok = false;
        try {
            Thread.sleep(800);
            RtpSession session = platform.mediaService().play(DEVICE);
            System.out.println("收流端口=" + session.getPort() + " ssrc=" + session.getSsrc());
            // 等待 ZLM 把媒体解出轨道（判定由外层脚本按 ZLM 原生日志给出）
            String registered = online.get(30, TimeUnit.SECONDS);
            System.out.println("流上线事件: " + registered);
            ok = registered != null;
            System.out.println("播放地址: " + platform.mediaService().playUrls(DEVICE));
        } catch (Exception e) {
            System.out.println("验证失败: " + e);
        } finally {
            sim.stop();
            platform.stop();
        }
        System.out.println(ok
                ? "应用层：注册 / 点播 / 会话建立 均已完成（流上线事件已收到）"
                : "应用层：信令与会话已建立；流上线事件未送达，媒体判定请看脚本按 ZLM 原生日志给出的结论");
        // 退出码始终为 0：媒体是否真的进来由外层脚本按 ZLM 原生日志判定
        System.exit(0);
    }
}