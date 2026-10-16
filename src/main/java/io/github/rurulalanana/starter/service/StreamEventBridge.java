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
 *
 * <p>云端录像的自动开/停也挂在这里：会话对象直接带 deviceId/channelId，
 * 不需要像 ZLM 层那样反查会话。见 {@link ChannelRecordPolicy}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamEventBridge implements RtpSessionManager.SessionListener {

    private final Gb28181MediaPlatform platform;
    private final StreamService streamService;
    private final DeviceService deviceService;
    private final RecordService recordService;
    private final PlatformAsyncExecutor asyncExecutor;
    private final ChannelRecordPolicy recordPolicy;

    @PostConstruct
    public void attach() {
        if (platform.sessionManager() == null) {
            log.warn("会话管理器尚未就绪，跳过收流事件挂载");
            return;
        }
        platform.sessionManager().setListener(this);
        // 通道云端录像开关是逐通道的库表数据，启动时载入内存镜像；
        // 放在 attach()（@PostConstruct）里而不是构造器：此时数据源已装配完毕
        recordPolicy.reload();
        // 同时挂 ZLM 原生流事件，覆盖「流上线但会话未跟踪」的场景。
        // 必须用 addStreamListener：媒体平台内部已注册了一个监听者驱动会话状态，
        // 用 set 会把它顶掉，导致会话永远停在 NEGOTIATING（画面能播但状态不变）。
        if (platform.mediaServer() != null) {
            platform.mediaServer().addStreamListener(new ZlmMediaServer.StreamListener() {
                @Override
                public void onStreamOnline(String app, String stream) {
                    // app=设备编码、stream=通道编码，拼起来就是 stream_key
                    asyncExecutor.run("流上线落库", () -> streamService.markOnline(app + "/" + stream));
                }

                @Override
                public void onStreamOffline(String app, String stream) {
                    asyncExecutor.run("流下线落库", () -> streamService.markOffline(app + "/" + stream));
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
        log.info("媒体流已上线: {} port={} ssrc={}",
                session.getSessionKey(), session.getPort(), session.getSsrc());
        asyncExecutor.run("流上线处理", () -> {
            deviceService.setChannelStatus(session.getDeviceId(), session.getChannelId(), "ON");
            streamService.markOnline(session.getSessionKey());
            // 通道开了云端录像 → 自动开始录制。此方法要求流已就绪，
            // 而会话上线事件本身就是流就绪信号，故可直接开录；
            // 失败只告警，不影响流本身。
            if (recordPolicy.isEnabled(session.getChannelId())
                    && !recordService.isRecording(session.getChannelId())) {
                try {
                    recordService.startRecordingDirect(session.getChannelId());
                    log.info("按通道开关自动开始录像: {}", session.getChannelId());
                } catch (Exception e) {
                    log.warn("自动录像启动失败: channel={} {}", session.getChannelId(), e.getMessage());
                }
            }
        });
    }

    @Override
    public void onSessionOffline(RtpSession session) {
        log.info("媒体流已下线（端口保留等待恢复）: {}", session.getSessionKey());
        asyncExecutor.run("流下线处理", () -> {
            streamService.markOffline(session.getSessionKey());
            // 流没了，ZLM 会自动停录；这里只负责把录像归档落库。
            // 必须在确认该通道确实在录时才归档，否则普通直播也会多打一次库。
            if (recordPolicy.isEnabled(session.getChannelId())
                    && recordService.isRecording(session.getChannelId())) {
                try {
                    recordService.archiveRecording(session.getChannelId());
                } catch (Exception e) {
                    log.warn("录像归档失败: channel={} {}", session.getChannelId(), e.getMessage());
                }
            }
        });
    }

    @Override
    public void onSessionClosed(RtpSession session) {
        log.info("会话已关闭: {}", session.getSessionKey());
        asyncExecutor.run("会话关闭处理", () -> {
            streamService.markClosed(session.getSessionKey());
            deviceService.setChannelStream(session.getDeviceId(), session.getChannelId(), null);
        });
    }
}
