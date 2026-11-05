package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSessionManager;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.mapper.GbStreamMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
/**
 * 收流会话事件 → 数据库的桥接器：把会话的打开 / 流上线 / 流下线 / 关闭同步到 {@code gb_stream}
 * 与通道状态，云端录像的自动开 / 停也挂在这里（会话对象直接带 deviceId/channelId）。
 *
 * <p>不用 {@code ZlmMediaServer.StreamListener} 作为主路径：后者由 ZLM 原生事件驱动（未注册推流鉴权时可能不投递），
 * 而 {@code RtpSessionManager} 在收到 RTP 时主动触发，更可靠。
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
    private final RuntimeConfigService runtimeConfig;
    private final RuntimeConfigApplier runtimeConfigApplier;
    private final GbStreamMapper streamMapper;

    @PostConstruct
    public void attach() {
        if (platform.sessionManager() == null) {
            log.warn("会话管理器尚未就绪，跳过收流事件挂载");
            return;
        }
        platform.sessionManager().setListener(this);
        // 归档钩子：必须在媒体源注销之前把录像落库 —— 结束时间 / 路径 / 时长来自 ZLM 的内存录像索引，
        // closeMediaSource 会把索引一并清掉，之后只能拿到 null（这也是「录像要等 60 秒对账」的根因）。
        // 钩子只能在这里注册：归档要查数据库，zlm 模块没有数据源。
        platform.sessionManager().setPreSourceCloseListener(
                session -> archiveRecordingQuietly(session, "媒体源注销"));
        // 孤儿流认领前必须校验设备在线：SSRC 会回绕复用，同一 SSRC 在库里可能对应多个通道，
        // 认错会把已离线的通道置为在线并长期挂着（实测踩过）。
        platform.sessionManager().setOrphanAdopter(deviceService::isDeviceOnline);
        // 恢复「SSRC → 通道」映射：下级平台不会因我方重启而停止推流，而 SSRC 索引是内存的，重启即空，
        // 这些流会认不出归属被丢弃（「级联还在推流、平台一路都看不到」）。
        int stale = streamService.closeStaleStreamsOnStartup();
        if (stale > 0) {
            log.info("已收敛 {} 条残留流状态为 CLOSED（平台重启后内存会话已清空）", stale);
        }
        // 通道的 status/stream_id 同样是上次运行的残留，不清会显示「通道在线」而实际一路都没有
        deviceService.resetStaleChannelStates();
        restoreSsrcMappings();
        // 放在 attach()（@PostConstruct）而不是构造器：此时数据源已装配完毕
        recordPolicy.reload();
        // 运行时配置同样要等数据源就绪（gb_config），顺序在 recordPolicy 之后（保活间隔依赖它）
        runtimeConfig.reload();
        // 必须在 reload 之后推送：reload 前缓存里只有 yml 默认值，推给 ZLM 会用错值（实测踩过）
        runtimeConfigApplier.applyAll();
        // 同时挂 ZLM 原生流事件，覆盖「流上线但会话未跟踪」；
        // 必须用 addStreamListener：媒体平台内部已注册了一个监听者驱动会话状态，
        // 用 set 会把它顶掉，会话永远停在 NEGOTIATING（画面能播但状态不变）。
        if (platform.mediaServer() != null) {
            platform.mediaServer().addStreamListener(new ZlmMediaServer.StreamListener() {
                @Override
                public void onStreamOnline(String app, String stream) {
                    // app=设备编码、stream=通道编码，拼起来就是 stream_key
                    asyncExecutor.run("流上线落库", () -> streamService.markOnline(app + "/" + stream));
                }

                /**
                 * 流从 ZLM 消失。
                 *
                 * <p><b>必须区分两种下线</b>：国标流是暂时的（设备重启 / 网络抖动，
                 * 会话与端口都保留着等它恢复），标 {@code NEGOTIATING} 正确；
                 * <b>SDK 流是真结束</b> —— 平台关掉发布器后 ZLM 里的媒体源就没了，
                 * 只有下次点播才会重建。若也标 {@code NEGOTIATING}，库里会留下
                 * 一条永不收敛的记录，{@code /stream/list} 一直显示它（推流数恒为 1），
                 * 而播放器打不开、读者数恒为 0。
                 *
                 * <p>顺带清掉 {@code gb_channel.stream_id}：那是通道列表判断
                 * 「有流 / 显示停止按钮」的依据，不清就永远显示在推流。
                 */
                @Override
                public void onStreamOffline(String app, String stream) {
                    String streamKey = app + "/" + stream;
                    asyncExecutor.run("流下线落库", () -> {
                        if (streamService.isSdkStream(streamKey)) {
                            /*
                             * ZLM 对一次流关闭会投递多次下线事件（每个协议/轨道各一次），
                             * 用返回值判断是否真的改了状态 —— 否则日志一次刷 5 条。
                             */
                            boolean changed = streamService.markClosed(streamKey);
                            deviceService.setChannelStream(app, stream, null);
                            if (changed) {
                                log.info("SDK 流已结束，记录置 CLOSED 并清除通道 stream_id: {}", streamKey);
                            }
                            return;
                        }
                        streamService.markOffline(streamKey);
                    });
                }
            });
        }
        log.info("收流事件桥已挂载（会话/流状态将落库）");
    }

    /**
     * 启动时从 {@code gb_stream} 恢复「SSRC → 会话键」映射。
     * 下级平台（实测 LiveGBS）收 BYE 回 200 却不停推流，我方重启对它无影响，它继续推同一个 SSRC；
     * 恢复映射后推流回调能反查出通道并自动重建会话，否则这些流一直不可见。
     *
     * <p>两条筛选缺一不可：设备必须在线（{@code state} 是上次运行残留，实测有 10 天前的 ONLINE）；
     * 同一 SSRC 只保留无歧义的一条（SSRC 按「平台编码 + 递增序号」生成且序号每次启动从 0 开始，
     * 实测 {@code 0200000002} 同时属于 3 条不同通道的记录，认错会把流挂到离线设备上）。
     */
    private void restoreSsrcMappings() {
        try {
            // 只取「所属设备当前在线」的通道：离线设备不可能在推流
            java.util.Set<String> onlineDevices = new java.util.HashSet<>();
            for (GbDevice d : deviceService.allDevices()) {
                if (Boolean.TRUE.equals(d.getOnline())) {
                    onlineDevices.add(d.getDeviceId());
                }
            }
            if (onlineDevices.isEmpty()) {
                log.info("无在线设备，跳过 SSRC 映射恢复");
                return;
            }
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(24);
            List<GbStream> rows = streamMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<GbStream>lambdaQuery()
                            .isNotNull(GbStream::getSsrc)
                            .ne(GbStream::getSsrc, "")
                            .eq(GbStream::getStreamType, GbStream.TYPE_LIVE)
                            .in(GbStream::getDeviceId, onlineDevices)
                            .ge(GbStream::getStartTime, since)
                            .orderByDesc(GbStream::getStartTime));
            // 按 SSRC 归并出「它可能属于哪些通道」。必须按歧义过滤、不能只取最新一条：
            // SSRC 由 GbId.buildSsrc 按「平台编码 + 递增序号」生成、序号每次 JVM 启动从 0 开始，
            // 实测 {@code 0200000001} 既是 EasyIPC 的通道、也是级联某路流在用的 SSRC，
            // 按「最新一条」恢复会把孤儿流认领到不相干的通道。
            java.util.Map<String, java.util.Set<String>> keysBySsrc = new java.util.LinkedHashMap<>();
            // SSRC → 该流最近一次对话的 Call-ID。认领出的孤儿流要靠它发 BYE
            // （没有 Call-ID 就只能本地注销媒体源，设备侧会一直推下去）。
            java.util.Map<String, String> callIdBySsrc = new java.util.LinkedHashMap<>();
            for (GbStream s : rows) {
                keysBySsrc.computeIfAbsent(s.getSsrc(), k -> new java.util.LinkedHashSet<>())
                        .add(s.getStreamKey());
                if (s.getCallId() != null && !s.getCallId().isBlank()) {
                    // rows 已按 start_time 倒序，取最新一条
                    callIdBySsrc.putIfAbsent(s.getSsrc(), s.getCallId());
                }
            }
            int ok = 0;
            int ambiguous = 0;
            for (java.util.Map.Entry<String, java.util.Set<String>> e : keysBySsrc.entrySet()) {
                if (e.getValue().size() > 1) {
                    // 有歧义就不恢复：代价只是「重启后自动重新纳管」失效，用户重新点播即可
                    ambiguous++;
                    continue;
                }
                if (platform.sessionManager().restoreSsrcMapping(
                        e.getKey(), e.getValue().iterator().next(),
                        callIdBySsrc.get(e.getKey()))) {
                    ok++;
                }
            }
            if (ambiguous > 0) {
                log.info("跳过 {} 个有歧义的 SSRC（同一 SSRC 对应多个通道，跨会话复用所致）", ambiguous);
            }
            if (ok > 0) {
                log.info("已恢复 {} 条 SSRC 映射（设备在线且重启前推过流的通道，重推时可自动重新纳管）", ok);
            }
        } catch (Exception e) {
            // 恢复失败不影响启动：后续人工点播仍能正常工作
            log.warn("恢复 SSRC 映射失败（不影响启动）: {}", e.getMessage());
        }
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
            // 通道开了云端录像 → 自动开录；会话上线本身就是流就绪信号。失败只告警，不影响流。
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
        // 状态落库走保序队列（快）
        asyncExecutor.run("流下线处理", () -> streamService.markOffline(session.getSessionKey()));
        // 归档走独立队列：内部要轮询等 ZLM 写终态（最多 3 秒），放保序队列会堵住所有心跳 / 状态落库，
        // 多路同时掉线时表现为「设备离线了界面还显示在线」。
        asyncExecutor.runArchive("流下线归档", () -> archiveRecordingQuietly(session, "流下线"));
    }

    @Override
    public void onSessionClosed(RtpSession session) {
        log.info("会话已关闭: {}", session.getSessionKey());
        asyncExecutor.run("会话关闭处理", () -> {
            streamService.markClosed(session.getSessionKey());
            deviceService.setChannelStream(session.getDeviceId(), session.getChannelId(), null);
        });
    }

    /**
     * 按通道开关归档录像（尽力而为，失败只告警）。
     * 只归档实时会话：回放 / 下载会话关闭时该通道的直播录像并没有结束，
     * 归档会拿到上一次已结束的历史条目重复入库（实测 14:47:24 出现过这种误归档）。
     * 不能用 {@code isRecording()} 做前置判断：归档时机就是「录制已结束」，该判断恒为 false。
     */
    private void archiveRecordingQuietly(RtpSession session, String reason) {
        if (session == null || !recordPolicy.isEnabled(session.getChannelId())) {
            return;
        }
        // 回放/下载会话的关闭不代表录像结束，归档会把历史条目重复入库
        if (session.getType() != null && session.getType().historical()) {
            return;
        }
        try {
            var archived = recordService.archiveRecording(session.getChannelId(), session.getCreatedAt());
            if (archived != null) {
                log.info("{}归档录像: channel={} recordId={} duration={}s",
                        reason, session.getChannelId(), archived.getRecordId(),
                        archived.getDuration());
            }
        } catch (Exception e) {
            log.warn("录像归档失败: channel={} reason={} {}",
                    session.getChannelId(), reason, e.getMessage());
        }
    }
}
