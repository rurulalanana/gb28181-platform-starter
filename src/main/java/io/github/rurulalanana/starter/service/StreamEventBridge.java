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
    private final GbStreamMapper streamMapper;

    @PostConstruct
    public void attach() {
        if (platform.sessionManager() == null) {
            log.warn("会话管理器尚未就绪，跳过收流事件挂载");
            return;
        }
        platform.sessionManager().setListener(this);
        // 归档钩子：会话关闭、媒体源注销【之前】把录像落库。
        //
        // 时机必须早于媒体源注销 —— 录像的结束时间/路径/时长来自 ZLM 的内存
        // 录像索引，closeMediaSource 会把索引一并清掉，之后再查只能拿到 null。
        // 这也是「录像要等 60 秒对账才补登记」的根因。
        //
        // 钩子只能在这里注册（starter）：归档要查数据库，zlm 模块没有数据源。
        platform.sessionManager().setPreSourceCloseListener(
                session -> archiveRecordingQuietly(session, "媒体源注销"));
        // 孤儿流认领的设备校验：SSRC 会回绕复用，同一 SSRC 在库里可能对应多个通道，
        // 认错会把一个已离线的通道置成在线并长期挂着（实测踩过）。
        // 离线设备不可能在推流，故认领前必须确认设备在线。
        platform.sessionManager().setOrphanAdopter(deviceService::isDeviceOnline);
        // 恢复「SSRC → 通道」映射：下级平台不会因我方重启而停止推流，
        // 它仍在往收流端口推同一个 SSRC。而 SSRC 索引是内存的，重启即空，
        // 这些流就会认不出归属被丢弃 —— 表现为「级联那边还在推流、
        // 平台这边一路都看不到」，直到设备自己超时或人工重新点播。
        // 先收敛残留状态再恢复映射：两者都读 gb_stream，顺序保证日志清晰。
        int stale = streamService.closeStaleStreamsOnStartup();
        if (stale > 0) {
            log.info("已收敛 {} 条残留流状态为 CLOSED（平台重启后内存会话已清空）", stale);
        }
        // 通道的 status/stream_id 同样是上次运行的残留：会话已随重启清空，
        // 离线设备的通道必然没有流。不清就会显示「通道在线」而实际一路都没有。
        deviceService.resetStaleChannelStates();
        restoreSsrcMappings();
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

    /**
     * 启动时从 {@code gb_stream} 恢复「SSRC → 会话键」映射。
     *
     * <p><b>解决什么问题</b>：下级平台（实测 LiveGBS）收 BYE 会回 200 OK
     * 却<b>不停止推流</b>，而且我方重启对它毫无影响 —— 它继续往收流端口推
     * 同一个 SSRC。此时：
     * <ol>
     *   <li>平台侧会话索引（内存）已空，设备推来的 RTP 认不出归属；</li>
     *   <li>单端口模式下 ZLM 拿 SSRC 反查不到会话，只能保留十六进制流名放行；</li>
     *   <li>结果就是「级联那边还在推流、平台这边一路都看不到」，
     *       直到设备自己超时或人工重新点播，白白占用带宽。</li>
     * </ol>
     *
     * <p>把库里的历史 SSRC 映射装回去后，设备下次推流时推流回调就能反查出通道，
     * 自动重建会话让流重新可见（见 {@code RtpSessionManager#reclaimOrphanBySsrc}）。
     *
     * <p><b>两条筛选规则，缺一不可</b>：
     * <ol>
     *   <li><b>设备必须在线</b>：{@code gb_stream.state} 是上次运行的残留，
     *       重启后完全不可信（实测有 10 天前的 ONLINE 记录）。设备离线时
     *       它不可能在推流，恢复其映射只会污染 SSRC 索引。</li>
     *   <li><b>同一 SSRC 只保留最新一条</b>：SSRC 会回绕复用（实测
     *       {@code 0200000002} 同时属于 3 条不同通道的记录）。若不去重，
     *       设备重推该 SSRC 时可能认领到错误的通道 —— 实测就出现过把流
     *       认领给一个已离线设备的情况。</li>
     * </ol>
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
            // 按 SSRC 归并出「它可能属于哪些通道」。
            //
            // <b>必须按歧义过滤，不能只取最新一条</b>：SSRC 由 {@code GbId.buildSsrc}
            // 按「平台编码 + 递增序号」生成，而序号每次 JVM 启动从 0 开始 ——
            // 于是同一个 SSRC 值会在不同设备、不同通道之间反复复用。
            // 实测 {@code 0200000001} 既出现在 EasyIPC 的通道上，也是级联平台
            // 某路流实际在用的 SSRC。若按「最新一条」恢复，就会把孤儿流认领到
            // 完全不相干的通道 —— 正是「离线设备的通道显示成在线」的成因。
            java.util.Map<String, java.util.Set<String>> keysBySsrc = new java.util.LinkedHashMap<>();
            for (GbStream s : rows) {
                keysBySsrc.computeIfAbsent(s.getSsrc(), k -> new java.util.LinkedHashSet<>())
                        .add(s.getStreamKey());
            }
            int ok = 0;
            int ambiguous = 0;
            for (java.util.Map.Entry<String, java.util.Set<String>> e : keysBySsrc.entrySet()) {
                if (e.getValue().size() > 1) {
                    // 有歧义：无法判断这路流属于哪个通道，宁可不恢复
                    // （不恢复的代价只是「重启后自动重新纳管」失效，用户重新点播即可）
                    ambiguous++;
                    continue;
                }
                if (platform.sessionManager().restoreSsrcMapping(
                        e.getKey(), e.getValue().iterator().next())) {
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
            // 流「异常」下线（设备拔线、ZLM 内部超时）时归档。
            // 主动 stop 不走这里 —— 它由 preSourceCloseListener 归档（见 attach）。
            archiveRecordingQuietly(session, "流下线");
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

    /**
     * 按通道开关归档录像（尽力而为，失败只告警）。
     *
     * <p><b>只归档实时会话</b>：回放/下载会话关闭时，该通道的实时录像
     * 并没有结束（可能还在录），此时归档会拿到上一次已结束的历史条目，
     * 把它当成"刚结束的录像"重复入库（实测 14:47:24 就出现了这种误归档）。
     * 归档只应由实时流的结束驱动。
     *
     * <p>不能用 {@code isRecording()} 做前置判断：归档时机是「录制已结束」，
     * 此时 ZLM 早已停录，该判断恒为 false（这正是归档长期失效的原因）。
     * {@code archiveRecording} 内部按 {@code endTime > 0} 过滤已结束条目，
     * 无条目可归档时立即返回 null，不会多打库。
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
