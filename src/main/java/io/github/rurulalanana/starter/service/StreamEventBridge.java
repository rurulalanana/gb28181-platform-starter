package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSessionManager;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 收流会话事件 → 数据库 的桥接器。
 *
 * <p>监听 {@link RtpSessionManager.SessionListener}，把会话的打开 / 流上线 /
 * 流下线 / 关闭同步到 {@code gb_stream} 与通道状态。
 *
 * <p>注意与 {@code ZlmMediaServer.StreamListener} 的区别：
 * 后者由 ZLM 原生事件驱动（可能因未注册推流鉴权而不投递），
 * 前者由 {@code RtpSessionManager} 内部在收到 RTP 时主动触发，更可靠。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamEventBridge implements RtpSessionManager.SessionListener {

    private final Gb28181MediaPlatform platform;
    private final StreamService streamService;
    private final DeviceService deviceService;
    private final PlatformAsyncExecutor asyncExecutor;

    @PostConstruct
    public void attach() {
        if (platform.sessionManager() == null) {
            log.warn("会话管理器尚未就绪，跳过收流事件挂载");
            return;
        }
        platform.sessionManager().setListener(this);
        // 同时挂 ZLM 原生流事件，覆盖「流上线但会话未跟踪」的场景。
        // 必须用 addStreamListener：媒体平台内部已注册了一个监听者驱动会话状态，
        // 用 set 会把它顶掉，导致会话永远停在 NEGOTIATING（画面能播但状态不变）。
        if (platform.mediaServer() != null) {
            platform.mediaServer().addStreamListener(new ZlmMediaServer.StreamListener() {
                @Override
                public void onStreamOnline(String app, String stream) {
                    asyncExecutor.run("流上线落库", () -> streamService.markOnline(app, stream));
                }

                @Override
                public void onStreamOffline(String app, String stream) {
                    asyncExecutor.run("流下线落库", () -> streamService.markOffline(app, stream));
                }
            });
        }
        log.info("收流事件桥已挂载（会话/流状态将落库）");
    }

    @Override
    public void onSessionOpened(RtpSession session) {
        log.info("会话已打开: {}", session);
        asyncExecutor.run("会话落库", () -> {
            var stream = streamService.toStream(session,
                    session.getType() != null && session.getType().historical()
                            ? io.github.rurulalanana.starter.entity.GbStream.TYPE_PLAYBACK
                            : io.github.rurulalanana.starter.entity.GbStream.TYPE_LIVE);
            streamService.save(stream);
        });
    }

    @Override
    public void onSessionOnline(RtpSession session) {
        log.info("媒体流已上线: channel={} port={} ssrc={}",
                session.getChannelId(), session.getPort(), session.getSsrc());
        asyncExecutor.run("流上线处理", () -> {
            deviceService.setChannelStatus(session.getChannelId(), "ON");
            streamService.markOnline(null, session.getStream());
        });
    }

    @Override
    public void onSessionOffline(RtpSession session) {
        log.info("媒体流已下线（端口保留等待恢复）: channel={}", session.getChannelId());
        asyncExecutor.run("流下线处理", () -> streamService.markOffline(null, session.getStream()));
    }

    @Override
    public void onSessionClosed(RtpSession session) {
        log.info("会话已关闭: channel={}", session.getChannelId());
        asyncExecutor.run("会话关闭处理", () -> {
            streamService.markClosed(session.getChannelId());
            deviceService.setChannelStream(session.getChannelId(), null);
        });
    }
}
