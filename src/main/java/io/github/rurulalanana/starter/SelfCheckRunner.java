package io.github.rurulalanana.starter;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 启动自检：应用起来后自动向第一台在线设备发起点播，便于快速验证
 * 「注册 → INVITE → 收流」链路是否打通。
 *
 * <p>开启方式：{@code --gb28181.starter.self-check=true}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfCheckRunner implements ApplicationRunner {

    private final Gb28181MediaPlatform platform;

    @Value("${gb28181.starter.self-check:false}")
    private boolean enabled;

    @Value("${gb28181.starter.self-check-delay-ms:3000}")
    private long delayMs;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                var registry = platform.sipServer().getRegistry();
                var online = registry.listAll().stream()
                        .filter(s -> registry.isOnline(s.getDeviceId()))
                        .findFirst();
                if (online.isEmpty()) {
                    log.warn("[自检] 没有在线设备，跳过");
                    return;
                }
                String deviceId = online.get().getDeviceId();
                log.info("[自检] 向 {} 发起点播…", deviceId);
                RtpSession session = platform.mediaService().play(deviceId);
                log.info("[自检] 收流端口={} ssrc={} 状态={}",
                        session.getPort(), session.getSsrc(), session.getState());
                PlayUrls urls = platform.mediaService().playUrls(deviceId);
                log.info("[自检] 播放地址: {}", urls);
            } catch (Exception e) {
                log.error("[自检] 失败", e);
            }
        }, "self-check");
        thread.setDaemon(true);
        thread.start();
    }
}