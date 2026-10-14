package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.MediaType;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
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
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
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
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码；{@code null} 或与设备编码相同表示设备自身
     * @param item      录像段（{@link RecordItem#ofFilePath} 可只带 filePath）
     */
    public GbStream startPlayback(String deviceId, String channelId, RecordItem item) {
        RtpSession session = platform.mediaService().playback(deviceId, channelId, item);
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
