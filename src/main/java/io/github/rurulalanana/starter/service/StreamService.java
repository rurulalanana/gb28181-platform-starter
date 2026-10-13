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
     * @param channelId 通道编号（= ZLM 流名）
     * @return 流信息
     */
    public GbStream startLive(String channelId) {
        RtpSession existing = platform.sessionManager().getByChannel(channelId);
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
            // 已有点播：直接复用，避免重复 INVITE 造成设备侧抖动
            return toStream(existing, GbStream.TYPE_LIVE);
        }
        GbChannel channel = deviceService.findChannel(channelId);
        if (channel != null && !channel.isOnline()) {
            throw BizException.badRequest("通道不在线：" + channelId);
        }
        // 平台重启后 SIP 注册表为空（纯内存），而设备要到下次心跳才自愈，
        // 空窗期内点播会报「设备未注册或会话已失效」。这里按库里的地址先补一次，
        // 让重启后立刻点播也能成功。
        healRegistryIfNeeded(channel);
        RtpSession session = platform.mediaService().play(channelId);
        GbStream stream = toStream(session, GbStream.TYPE_LIVE);
        save(stream);
        deviceService.setChannelStream(channelId, stream.getStreamKey());
        return stream;
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
     * @param item 录像段（{@link RecordItem#ofFilePath} 可只带 filePath）
     */
    public GbStream startPlayback(String channelId, RecordItem item) {
        RtpSession session = platform.mediaService().playback(channelId, item);
        GbStream stream = toStream(session, GbStream.TYPE_PLAYBACK);
        save(stream);
        return stream;
    }

    /** 停止某通道的流（发 BYE 释放设备带宽 + 关闭收流端口）。 */
    public boolean stop(String channelId) {
        RtpSession session = platform.sessionManager().getByChannel(channelId);
        if (session == null) {
            return false;
        }
        String callId = session.getCallId();
        String deviceId = session.getDeviceId();
        // 发 BYE 让设备停止推流，否则设备会持续占用上行带宽
        if (callId != null && deviceId != null && platform.client() != null) {
            try {
                platform.client().bye(deviceId, callId, session.getToTag());
            } catch (Exception e) {
                log.warn("发送 BYE 失败: channel={} callId={} {}", channelId, callId, e.getMessage());
            }
        }
        platform.sessionManager().closeByChannel(channelId);
        markClosed(channelId);
        deviceService.setChannelStream(channelId, null);
        return true;
    }

    /** 停止全部流。 */
    public int stopAll() {
        List<RtpSession> sessions = new ArrayList<>(platform.sessionManager().sessions());
        for (RtpSession s : sessions) {
            stop(s.getChannelId());
        }
        return sessions.size();
    }

    /** 按 Call-ID 停止（BYE 处理路径）。 */
    public void stopByCallId(String callId) {
        RtpSession session = platform.sessionManager().getByCallId(callId);
        if (session != null) {
            markClosed(session.getChannelId());
        }
    }

    // ==================== 查询 ====================

    /** 某通道当前会话。 */
    public RtpSession session(String channelId) {
        return platform.sessionManager().getByChannel(channelId);
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
        String stream = session.getStream();
        PlayUrls urls = platform.mediaServer() == null ? null : platform.mediaServer().playUrls(stream);
        vo.setStreamId(stream);
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
        GbChannel channel = deviceService.findChannel(session.getChannelId());
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
        s.setStreamKey(session.getStream());
        s.setChannelId(session.getChannelId());
        s.setDeviceId(session.getDeviceId());
        s.setStreamName(session.getStream());
        s.setApp(props.getApp());
        s.setSsrc(session.getSsrc());
        s.setRtpPort(session.getPort());
        s.setStreamType(type);
        s.setState(session.getState());
        s.setCallId(session.getCallId());
        s.setTransport(session.getTcpMode() == 1 ? "TCP" : "UDP");
        s.setStartTime(TimeFormats.ofEpochMilli(session.getCreatedAt()));
        s.setUpdateTime(LocalDateTime.now());
        PlayUrls urls = platform.mediaServer() == null ? null
                : platform.mediaServer().playUrls(session.getStream());
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

    /** 流上线。
     *
     * <p>ZLM 上报的 {@code (app, stream)} 有两种布局，两种都要认（详见
     * {@code RtpSessionManager.findByStream}）：{@code app=rtp, stream=<通道>/<序号>}
     * 与 {@code app=<通道>, stream=<序号>}。因此不能用 app 做过滤条件，
     * 只能靠「能否映射到已知通道」来判定。
     *
     * @param app    ZLM 应用名（可能是 {@code rtp}，也可能是通道编码）
     * @param stream ZLM 流名（{@code <通道编码>} 或 {@code <序号>}）
     */
    public void markOnline(String app, String stream) {
        String channelId = channelKeyOf(app, stream);
        if (channelId == null) {
            return;
        }
        GbStream record = findByChannel(channelId, GbStream.TYPE_LIVE);
        if (record == null) {
            record = findByChannel(channelId, GbStream.TYPE_PLAYBACK);
        }
        if (record != null) {
            record.setState(GbStream.STATE_ONLINE);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
        }
        if (deviceService.findChannel(channelId) != null) {
            deviceService.setChannelStatus(channelId, "ON");
        }
    }

    /** 流下线（端口保留，等设备恢复）。参数含义同 {@link #markOnline}。 */
    public void markOffline(String app, String stream) {
        String channelId = channelKeyOf(app, stream);
        if (channelId == null) {
            return;
        }
        GbStream record = findByChannel(channelId, GbStream.TYPE_LIVE);
        if (record == null) {
            record = findByChannel(channelId, GbStream.TYPE_PLAYBACK);
        }
        if (record != null && !GbStream.STATE_CLOSED.equals(record.getState())) {
            record.setState(GbStream.STATE_NEGOTIATING);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
        }
    }

    /** 标记通道流已关闭。 */
    public void markClosed(String channelId) {
        for (GbStream s : streamMapper.selectList(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getChannelId, channelId)
                .ne(GbStream::getState, GbStream.STATE_CLOSED))) {
            s.setState(GbStream.STATE_CLOSED);
            s.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(s);
        }
    }

    /** 按类型查某通道的当前流记录。 */
    public GbStream findByChannel(String channelId, String type) {
        return streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getChannelId, channelId)
                .eq(GbStream::getStreamType, type)
                .ne(GbStream::getState, GbStream.STATE_CLOSED)
                .orderByDesc(GbStream::getStartTime)
                .last("limit 1"));
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

    /**
     * 把 ZLM 上报的 {@code (app, stream)} 归一成通道编码。
     *
     * <p>ZLM 的两种流标识布局：
     * <ul>
     *   <li>{@code app=rtp, stream=<通道编码>/<序号>} —— 取 stream 的斜杠前部分</li>
     *   <li>{@code app=<通道编码>, stream=<序号>} —— 取 app</li>
     * </ul>
     * 都不是则返回 null（无法归属到任何通道，直接忽略）。
     */
    private String channelKeyOf(String app, String stream) {
        if (stream != null) {
            int slash = stream.lastIndexOf('/');
            if (slash > 0) {
                return stream.substring(0, slash);
            }
        }
        if (app != null && !app.isBlank() && !props.getApp().equals(app)) {
            return app;
        }
        // app=rtp 且 stream 不带斜杠：stream 可能就是通道编码
        return stream == null || stream.isBlank() ? null : stream;
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
