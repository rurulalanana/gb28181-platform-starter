package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.MediaType;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSessionManager;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.mapper.GbStreamMapper;
import io.github.rurulalanana.starter.vo.StreamVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播流与回放流的生命周期管理。
 *
 * <p>把 ZLM 的 {@link RtpSession} 与数据库 {@code gb_stream} 表双向同步：
 * <ul>
 *   <li>发起点播 / 回放时落库（状态 {@code NEGOTIATING}）</li>
 *   <li>ZLM 流上线回调时置 {@code ONLINE}</li>
 *   <li>停止 / BYE / 流下线时置 {@code CLOSED}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamService {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;
    private final GbStreamMapper streamMapper;
    private final DeviceService deviceService;
    private final SipRegistryHealer registryHealer;

    // ==================== 点播 ====================

    /**
     * 开始实时直播（标准 9.2）。
     *
     * @param deviceId  设备编码（SIP 目标，全局唯一）
     * @param channelId 通道编码（= ZLM 流名）；{@code null} 或与设备编码相同表示设备自身
     * @return 流信息
     */
    public GbStream startLive(String deviceId, String channelId) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        RtpSession existing = platform.sessionManager().getByChannel(deviceId, channel);
        if (existing != null && isReusable(existing)) {
            // 已有点播：直接复用，避免重复 INVITE 造成设备侧抖动
            return toStream(existing, GbStream.TYPE_LIVE);
        }
        GbChannel ch = deviceService.findChannel(deviceId, channel);
        if (ch != null && !ch.isOnline()) {
            throw BizException.badRequest("通道不在线：" + channel);
        }
        // 平台重启后 SIP 注册表为空（纯内存），而设备要到下次心跳才自愈，
        // 空窗期内点播会报「设备未注册或会话已失效」。这里按库里的地址先补一次，
        // 让重启后立刻点播也能成功。
        healRegistryIfNeeded(ch);
        RtpSession session = platform.mediaService().play(deviceId, channel);
        GbStream stream = toStream(session, GbStream.TYPE_LIVE);
        save(stream);
        deviceService.setChannelStream(deviceId, channel, stream.getStreamKey());
        return stream;
    }

    /**
     * 既有会话能否直接复用给新的点播请求。
     *
     * <p>判据有三条，缺一不可：
     * <ol>
     *   <li><b>类型必须是直播</b>：回放/下载与直播是不同会话（键带类型后缀），
     *       但若键上残留着历史会话（如旧版本数据），复用会让「点直播」拿到回放的流。
     *       实测过：重试点播后返回的 {@code filePath} 指向录像文件。</li>
     *   <li><b>状态不能是 CLOSED</b>：已关闭的会话其端口与媒体源都已释放。</li>
     *   <li><b>流必须真的在推</b>：流下线只把状态置为 {@code NEGOTIATING}
     *       （不是 CLOSED，端口保留等待设备恢复），此时复用会拿到一个
     *       播不出画面的死会话 —— 用户看到「点了播放没反应」。
     *       必须问 ZLM 是否真的在收 RTP 包。</li>
     * </ol>
     *
     * <p>不可复用时返回 false，调用方会走完整点播流程（重建会话 + INVITE），
     * 这是恢复死会话的唯一手段。
     */
    private boolean isReusable(RtpSession session) {
        if (session == null || RtpSession.CLOSED.equals(session.getState())) {
            return false;
        }
        if (session.getType() != null && session.getType() != MediaType.REALTIME) {
            return false;
        }
        // 已 ONLINE 说明 ZLM 已解出轨道，可直接复用；NEGOTIATING 需实测是否有流
        if (RtpSession.ONLINE.equals(session.getState())) {
            return true;
        }
        var media = platform.mediaServer();
        return media != null && media.isRtpReceiving(session.getZlmApp(), session.getStream());
    }

    /**
     * 开始实时直播（设备直连：设备编码即通道编码）。
     *
     * <p>适用于直连摄像机或设备自身就是一路通道的场景；
     * 下挂子通道请用 {@link #startLive(String, String)}。
     */
    public GbStream startLive(String deviceId) {
        return startLive(deviceId, deviceId);
    }

    /** 注册表缺该通道所属设备时，按数据库记录重建会话。 */
    private void healRegistryIfNeeded(GbChannel channel) {
        if (channel == null || channel.getDeviceId() == null) {
            return;
        }
        try {
            GbDevice device = deviceService.findDevice(channel.getDeviceId());
            if (device == null) {
                return;
            }
            registryHealer.healFromDb(device.getDeviceId(), device.getRemoteIp(),
                    device.getRemotePort(), device.getTransport());
        } catch (Exception e) {
            log.warn("点播前注册表自愈失败（不阻断点播）: {}", e.getMessage());
        }
    }

    /**
     * 开始历史回放（标准 9.8）。
     *
     * <p><b>必须与直播隔离</b>：回放与直播是独立会话（会话键带 {@code _playback} 后缀，
     * ZLM 侧流名也带后缀），因此「有人看直播时另一个人点回放」不会互相顶替。
     * 早先两者共用同一会话键，回放会走 {@code open()} 的「先关闭旧会话」分支，
     * 把正在观看的直播踢掉（实测日志：{@code 已有会话，先关闭旧会话: type=REALTIME,
     * state=ONLINE} 紧跟 {@code 打开收流会话: type=PLAYBACK}，观看端画面直接中断）。
     *
     * <p><b>同一通道的重复回放</b>：复用已在播的回放会话，避免重复 INVITE
     * 造成设备侧抖动与重复推流。
     *
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @param item      录像段（{@link RecordItem#ofFilePath} 可只带 filePath）
     */
    public GbStream startPlayback(String deviceId, String channelId, RecordItem item) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        RtpSession existing = platform.sessionManager()
                .getByChannel(deviceId, channel, MediaType.PLAYBACK);
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
            // 同一段录像已在回放：复用；不同段则重开（下面的 play 会先关旧会话）
            String want = item == null ? null : item.getFilePath();
            String have = existing.getMediaUri();
            if (want == null || want.equals(have)) {
                return toStream(existing, GbStream.TYPE_PLAYBACK);
            }
        }
        RtpSession session = platform.mediaService().playback(deviceId, channel, item);
        GbStream stream = toStream(session, GbStream.TYPE_PLAYBACK);
        save(stream);
        return stream;
    }

    /** 开始历史回放（设备直连：设备编码即通道编码）。 */
    public GbStream startPlayback(String deviceId, RecordItem item) {
        return startPlayback(deviceId, deviceId, item);
    }

    /**
     * 停止某通道的流（发 BYE 释放设备带宽 + 关闭收流端口）。
     *
     * <p>BYE 与本地释放统一由 {@link Gb28181MediaService#stop(String, String)}
     * 负责，避免两条停止路径（原生接口 / 业务接口）行为不一致——
     * 早先 {@code /api/stop} 只释放本地资源不发 BYE，设备会继续推流。
     *
     * <p>数据库状态由 {@code StreamEventBridge.onSessionClosed} 回写
     * （{@code gb_stream} 置 CLOSED、通道 stream_id 清空），此处不重复处理。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @return 是否找到并关闭了会话
     */
    public boolean stop(String deviceId, String channelId) {
        return platform.mediaService().stop(deviceId, channelId);
    }

    /** 停止某通道的流（设备直连：设备编码即通道编码）。 */
    public boolean stop(String deviceId) {
        return stop(deviceId, deviceId);
    }

    /**
     * 停止某通道指定类型的流。
     *
     * <p>回放/下载与直播是独立会话（键带类型后缀），停回放必须走本方法，
     * 否则会去关直播会话或关不到任何会话。
     *
     * @param type {@code null} 或 {@link MediaType#REALTIME} 等价于
     *             {@link #stop(String, String)}
     */
    public boolean stop(String deviceId, String channelId, MediaType type) {
        return platform.mediaService().stop(deviceId, channelId, type);
    }

    /** 停止全部流。 */
    public int stopAll() {
        List<RtpSession> sessions = new ArrayList<>(platform.sessionManager().sessions());
        for (RtpSession s : sessions) {
            stop(s.getDeviceId(), s.getChannelId());
        }
        return sessions.size();
    }

    /** 按 Call-ID 停止（BYE 处理路径）。 */
    public void stopByCallId(String callId) {
        RtpSession session = platform.sessionManager().getByCallId(callId);
        if (session != null) {
            markClosed(session.getSessionKey());
        }
    }

    // ==================== 查询 ====================

    /**
     * 某通道当前会话。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     */
    public RtpSession session(String deviceId, String channelId) {
        return platform.sessionManager().getByChannel(deviceId, channelId);
    }

    /**
     * 某通道当前会话（设备直连：设备编码即通道编码）。
     *
     * <p><b>注意</b>：通道编码跨设备可能重复，多设备环境请用
     * {@link #session(String, String)} 精确指定。
     */
    public RtpSession session(String channelId) {
        return platform.sessionManager().getByChannel(channelId);
    }

    /**
     * 按会话键取会话（{@code <设备编码>/<通道编码>}）。
     *
     * <p>与 ZLM 的 {@code app/stream} 一致，是会话的唯一标识。
     */
    public RtpSession sessionByKey(String sessionKey) {
        return platform.sessionManager().getByKey(sessionKey);
    }

    /** 当前全部会话。 */
    public List<RtpSession> sessions() {
        return new ArrayList<>(platform.sessionManager().sessions());
    }

    /** 会话 → 返回体。 */
    public StreamVo describe(RtpSession session) {
        StreamVo vo = new StreamVo();
        if (session == null) {
            return vo;
        }
        PlayUrls urls = platform.mediaServer() == null
                ? null : platform.mediaServer().playUrls(session);
        // streamId 用「设备编码/通道编码」复合标识：通道编码跨设备可重复，
        // 单独返回通道编码会让前端无法区分两台设备的同号通道
        vo.setStreamId(session.getSessionKey());
        vo.setDeviceId(session.getDeviceId());
        vo.setChannelId(session.getChannelId());
        vo.setSmsId(props.getPlatformId());
        vo.setSsrc(session.getSsrc());
        vo.setRtpPort(session.getPort());
        vo.setState(session.getState());
        vo.setTransport(session.getTcpMode() == 1 ? "TCP" : "UDP");
        if (urls != null) {
            vo.setWebrtc(urls.getWebrtc());
            vo.setFlv(urls.getHttpFlv());
            vo.setWsFlv(urls.getWsFlv());
            vo.setHls(urls.getHls());
            vo.setRtsp(urls.getRtsp());
            vo.setRtmp(urls.getRtmp());
        }
        GbChannel channel = deviceService.findChannel(session.getDeviceId(), session.getChannelId());
        if (channel != null) {
            vo.setChannelName(channel.displayName());
            vo.setChannelPtzType(channel.getPtzType());
            vo.setDeviceName(deviceNameOf(channel.getDeviceId()));
        }
        vo.setStartAt(TimeFormats.ofEpochMilli(session.getCreatedAt()).format(TimeFormats.DISPLAY));
        vo.setDuration(Math.max(0,
                (System.currentTimeMillis() - session.getCreatedAt()) / 1000));
        vo.setCallId(session.getCallId());
        if (session.getMediaUri() != null) {
            vo.setFilePath(session.getMediaUri());
        }
        return vo;
    }

    /** 会话 → 数据库实体。 */
    public GbStream toStream(RtpSession session, String type) {
        GbStream s = new GbStream();
        // stream_key 用复合标识（设备/通道），与 ZLM 的 app/stream 一致，
        // 保证唯一约束不会因两台设备同号通道而冲突
        s.setStreamKey(session.getSessionKey());
        s.setChannelId(session.getChannelId());
        s.setDeviceId(session.getDeviceId());
        s.setStreamName(session.getStream());
        s.setApp(session.getZlmApp());
        s.setSsrc(session.getSsrc());
        s.setRtpPort(session.getPort());
        s.setStreamType(type);
        s.setState(session.getState());
        s.setCallId(session.getCallId());
        s.setTransport(session.getTcpMode() == 1 ? "TCP" : "UDP");
        s.setStartTime(TimeFormats.ofEpochMilli(session.getCreatedAt()));
        s.setUpdateTime(LocalDateTime.now());
        PlayUrls urls = platform.mediaServer() == null
                ? null : platform.mediaServer().playUrls(session);
        if (urls != null) {
            s.setHttpFlv(urls.getHttpFlv());
            s.setWsFlv(urls.getWsFlv());
            s.setHls(urls.getHls());
            s.setWebrtc(urls.getWebrtc());
            s.setRtsp(urls.getRtsp());
            s.setRtmp(urls.getRtmp());
        }
        return s;
    }

    /**
     * 插入或更新流记录。
     *
     * <p>{@code gb_stream.stream_key} 上有唯一约束，而 ZLM 的「流注册」事件
     * 可能重复投递（重新点播、转协议重连等），并发下「先查再插」会撞唯一键。
     * 因此这里捕获冲突后改为按 stream_key 更新，保证幂等。
     */
    public void save(GbStream stream) {
        if (stream == null || stream.getStreamKey() == null) {
            return;
        }
        GbStream existing = streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getStreamKey, stream.getStreamKey()).last("limit 1"));
        if (existing != null) {
            stream.setId(existing.getId());
            streamMapper.updateById(stream);
            return;
        }
        try {
            streamMapper.insert(stream);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发插入撞唯一键：退化为更新
            GbStream current = streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                    .eq(GbStream::getStreamKey, stream.getStreamKey()).last("limit 1"));
            if (current == null) {
                throw e;
            }
            stream.setId(current.getId());
            streamMapper.updateById(stream);
        }
    }

    /**
     * 流上线。
     *
     * @param streamKey 复合流标识 {@code <设备编码>/<通道编码>}
     */
    public void markOnline(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return;
        }
        GbStream record = findByStreamKey(streamKey);
        if (record == null) {
            return;
        }
        if (!GbStream.STATE_ONLINE.equals(record.getState())) {
            record.setState(GbStream.STATE_ONLINE);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
        }
        if (deviceService.findChannel(record.getDeviceId(), record.getChannelId()) != null) {
            deviceService.setChannelStatus(record.getDeviceId(), record.getChannelId(), "ON");
        }
    }

    /** 流下线（端口保留，等设备恢复）。参数含义同 {@link #markOnline}。 */
    public void markOffline(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return;
        }
        GbStream record = findByStreamKey(streamKey);
        if (record != null && !GbStream.STATE_CLOSED.equals(record.getState())) {
            record.setState(GbStream.STATE_NEGOTIATING);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
        }
    }

    /**
     * 标记流已关闭。
     *
     * @param streamKey 复合流标识 {@code <设备编码>/<通道编码>}
     */
    public void markClosed(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return;
        }
        GbStream record = findByStreamKey(streamKey);
        if (record != null && !GbStream.STATE_CLOSED.equals(record.getState())) {
            record.setState(GbStream.STATE_CLOSED);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
        }
    }

    /**
     * 平台启动时把库里残留的「非 CLOSED」流记录收敛为 CLOSED。
     *
     * <p><b>为什么必须做</b>：{@code gb_stream.state} 反映的是「平台内存里的会话状态」，
     * 而会话是纯内存对象 —— 平台一重启就全没了，可库里还留着上次运行的
     * {@code ONLINE} / {@code NEGOTIATING}。这些残留会误导用户（界面显示「推流中」
     * 但实际一路都没有），也会干扰按状态做的统计与判断。
     *
     * <p>重启后内存为空是既成事实，所以统一置 CLOSED 是准确的：
     * 平台此刻确实没有任何会话。之后设备重新推流或被点播时，会话重建会
     * 把状态改回 ONLINE。
     *
     * @return 收敛的记录条数
     */
    public int closeStaleStreamsOnStartup() {
        try {
            List<GbStream> stale = streamMapper.selectList(
                    Wrappers.<GbStream>lambdaQuery()
                            .ne(GbStream::getState, GbStream.STATE_CLOSED));
            if (stale.isEmpty()) {
                return 0;
            }
            LocalDateTime now = LocalDateTime.now();
            for (GbStream s : stale) {
                s.setState(GbStream.STATE_CLOSED);
                s.setUpdateTime(now);
                streamMapper.updateById(s);
            }
            return stale.size();
        } catch (Exception e) {
            log.warn("收敛残留流状态失败（不影响启动）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 把「设备仍在推、平台却没有会话」的孤儿流重新纳管。
     *
     * <p><b>为什么需要</b>：{@code on_mk_media_publish} 回调只在流<b>首次注册</b>
     * 时触发一次，而设备（下级平台，实测 LiveGBS）收 BYE 回 200 OK 却不停止推流，
     * 且我方重启对它毫无影响。于是存在这样的时序竞争：平台启动时设备已经在推，
     * 回调先到、SSRC 映射后恢复 —— 实测两者相差 1.4 秒，这路流就永久停在
     * 「未匹配到会话」，表现为「级联那边还在推流、平台这边一路都看不到」，
     * 白白占用带宽直到设备自己超时。
     *
     * <p>做法：枚举 ZLM 里流名仍是 SSRC 十六进制串的媒体源（= 未被纳管的标志），
     * 逐个交给会话管理器按 SSRC 墓碑重建会话。重建后 ZLM 侧的流名不会变
     * （{@code stream_replace} 只在注册时生效），但平台已有会话、能出播放地址，
     * 用户重新点播时也会因会话已存在而直接复用。
     *
     * @return 成功纳管的流数
     */
    public int adoptOrphanStreams() {
        ZlmMediaServer mediaServer = platform.mediaServer();
        RtpSessionManager sessions = platform.sessionManager();
        if (mediaServer == null || sessions == null) {
            return 0;
        }
        int adopted = 0;
        for (String name : mediaServer.orphanStreamNames()) {
            try {
                // 先看是否已被纳管：认领后的会话其 zlmStream 正是这个十六进制名
                // （stream_replace 改不动已注册的流），所以「流名是十六进制」
                // 不再等价于「未纳管」。不排除的话每轮巡检都会把已纳管的流
                // 再报一次「已重新纳管」，日志与实际不符。
                RtpSession known = sessions.resolveBySsrcName(name);
                if (known != null && !RtpSession.CLOSED.equals(known.getState())) {
                    continue;
                }
                if (sessions.reclaimOrphanBySsrc(name) != null) {
                    adopted++;
                }
            } catch (Exception e) {
                log.debug("纳管孤儿流失败: stream={} {}", name, e.getMessage());
            }
        }
        return adopted;
    }

    /**
     * 空闲流巡检：关掉「没有任何观看者」的会话。
     *
     * <p><b>为什么不能只靠 ZLM 的无人观看回调</b>：{@code on_mk_media_no_reader}
     * <b>每个媒体源只触发一次</b>（ZLM 内部 {@code _none_reader} 标志位），
     * 且只在读者数由非零降为零的那一刻触发。于是存在一段无法覆盖的空窗：
     * <ol>
     *   <li>平台重启后设备仍在推流，回调在会话重建<b>之前</b>就已触发过
     *       （实测相差 2 秒），之后永不重来；</li>
     *   <li>{@link #adoptOrphanStreams()} 重新纳管时流早已注册，回调不会补发。</li>
     * </ol>
     * 结果就是这些流既不会被关掉、也永远不会再触发回调 —— 一直占着带宽和收流端口，
     * 直到设备自己超时（而实测 LiveGBS 根本不会超时）。必须由平台自己定期主动检测。
     *
     * <p><b>判据是 ZLM 的读者数而不是平台自己的观看计数</b>：ZLM 才知道真相 ——
     * 它把 MP4 录制器也算作读者（{@code protocol.mp4_as_player=1}），
     * 所以「只开录像没人看」的通道不会被误关；而正在被播放的流读者数必然大于 0。
     *
     * <p><b>宽限期</b>：刚建立的会话读者数天然是 0（还没人来得及播放），
     * 故只处理「已 ONLINE 超过宽限期」的会话。宽限期取 ZLM 的
     * {@code streamNoneReaderDelayMS}，与回调语义保持一致。
     *
     * @return 关闭的会话数
     */
    public int reapIdleStreams() {
        ZlmMediaServer mediaServer = platform.mediaServer();
        RtpSessionManager sessions = platform.sessionManager();
        if (mediaServer == null || sessions == null) {
            return 0;
        }
        long graceMs = Math.max(20000L, mediaServer.noneReaderDelayMs());
        long now = System.currentTimeMillis();
        int closed = 0;
        for (RtpSession session : sessions.sessions()) {
            try {
                if (session == null || RtpSession.CLOSED.equals(session.getState())) {
                    continue;
                }
                // 未 ONLINE 的会话（NEGOTIATING）由 INVITE 超时机制负责，
                // 这里不碰：它可能正在等设备回 SDP
                if (!RtpSession.ONLINE.equals(session.getState())) {
                    continue;
                }
                if (now - session.getCreatedAt() < graceMs) {
                    continue;
                }
                if (mediaServer.readerCount(session.getZlmApp(), session.getStream()) > 0) {
                    continue;
                }
                log.info("空闲流巡检：{} 秒内无观看者，关闭会话 {}", graceMs / 1000, session.getSessionKey());
                // 先记冷却再关流：设备不认 BYE 时它会继续推，
                // 不记冷却就会被孤儿流巡检立刻认领回来，形成无限循环。
                // 按会话键记（不是 SSRC —— 会话的 ssrc 是 SDP 通告值，
                // 与设备实际推流用的 RTP SSRC 不同，实测关流 11 秒后又被认领回来）。
                sessions.markReaped(session.getSessionKey());
                sessions.closeByKey(session.getSessionKey());
                closed++;
            } catch (Exception e) {
                log.debug("关闭空闲流失败: {}", e.getMessage());
            }
        }
        return closed;
    }

    /**
     * 按类型查某通道的当前流记录。
     *
     * <p>用「设备编码 + 通道编码」精确定位：通道编码跨设备可重复，
     * 只按通道编码查会拿到别的设备的同号通道记录。
     *
     * @param deviceId  设备编码；{@code null} 时退化为只按通道编码查
     * @param channelId 通道编码
     */
    public GbStream findByChannel(String deviceId, String channelId, String type) {
        return streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getChannelId, channelId)
                .eq(deviceId != null && !deviceId.isBlank(), GbStream::getDeviceId, deviceId)
                .eq(GbStream::getStreamType, type)
                .ne(GbStream::getState, GbStream.STATE_CLOSED)
                .orderByDesc(GbStream::getStartTime)
                .last("limit 1"));
    }

    /**
     * 按类型查某通道的当前流记录（设备直连：设备编码即通道编码）。
     *
     * <p><b>注意</b>：通道编码跨设备可能重复，多设备环境请用
     * {@link #findByChannel(String, String, String)}。
     */
    public GbStream findByChannel(String channelId, String type) {
        return findByChannel(null, channelId, type);
    }

    /** 按流名查记录。 */
    public GbStream findByStreamKey(String streamKey) {
        return streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getStreamKey, streamKey).last("limit 1"));
    }

    /** 直播流列表（含内存中会话，保证「刚点播未落库」也能查到）。 */
    public List<StreamVo> liveList() {
        List<StreamVo> out = new ArrayList<>();
        for (RtpSession s : sessions()) {
            if (s.getType() == MediaType.REALTIME) {
                out.add(describe(s));
            }
        }
        return out;
    }

    /** 回放流列表。 */
    public List<StreamVo> playbackList() {
        List<StreamVo> out = new ArrayList<>();
        for (RtpSession s : sessions()) {
            if (s.getType() != null && s.getType().historical()) {
                out.add(describe(s));
            }
        }
        return out;
    }

    private String deviceNameOf(String deviceId) {
        var d = deviceService.findDevice(deviceId);
        return d == null ? null : d.displayName();
    }

    /** 平台媒体服务是否可用。 */
    public boolean mediaAvailable() {
        return platform.mediaServer() != null;
    }

    /** 当前会话数。 */
    public int sessionCount() {
        return platform.sessionManager() == null ? 0 : platform.sessionManager().sessionCount();
    }

    /** 媒体服务。 */
    public Gb28181MediaService mediaService() {
        return platform.mediaService();
    }
}
