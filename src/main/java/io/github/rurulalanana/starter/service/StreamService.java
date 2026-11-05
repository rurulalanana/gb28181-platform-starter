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
import io.github.rurulalanana.starter.common.StreamIndex;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.mapper.GbStreamMapper;
import io.github.rurulalanana.starter.vo.StreamListVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;
import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播流与回放流的生命周期管理：把 ZLM 的 {@link RtpSession} 与 {@code gb_stream} 表双向同步。
 * 点播 / 回放时落库（{@code NEGOTIATING}），流上线置 {@code ONLINE}，停止 / BYE / 流下线置 {@code CLOSED}。
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

    /**
     * 「此刻是否应该录」的权威判定（用户开关 ∪ 录像计划）。
     * 空闲流回收不能直接读 {@code gb_channel.cloud_record}：录像计划不写库，
     * 只读库会把「计划刚开录」的流当成无读者回收掉。
     */
    private final ChannelRecordPolicy recordPolicy;

    /**
     * 各通道上一次点播用的码流编号。
     *
     * <p><b>为什么需要单独记</b>：码流编号是 INVITE 时写进 SSRC 后 4 位的，
     * 而 {@code AUTO} 模式下这 4 位是域内自增序号 —— 光看会话的 SSRC
     * 无法反推出「用户当时要的是哪一路码流」。不记的话，
     * 从子码流切回主码流会命中复用分支，画面还是子码流，且不报任何错。
     *
     * <p>只存内存：会话本身也在内存（{@code RtpSessionManager}），
     * 进程重启后两者一起丢，不存在不一致。
     */
    private final java.util.Map<String, Integer> requestedStreams =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 码流记录的键：与 {@code gb_stream.stream_key} 同构。 */
    private static String streamKey(String deviceId, String channel) {
        return deviceId + "/" + channel;
    }

    /** 非国标设备的接入实现（大华 SDK 等）：点播要拿它的 RTSP 地址。 */
    private final DeviceAccessRegistry accessRegistry;

    /**
     * 大华 SDK 取流（NAT 场景下唯一能出图的路径）。
     *
     * <p>用 {@code ObjectProvider} 注入而不是直接构造参数：没有大华 SDK 的部署
     * 不该因为这个 bean 缺失而起不来（同 {@code DeviceAccessRegistry} 的策略）。
     */
    private final org.springframework.beans.factory.ObjectProvider<DahuaSdkStreamService>
            dahuaSdkStreamProvider;

    /** 解析一次并缓存，避免每次点播都查容器。 */
    private volatile DahuaSdkStreamService dahuaSdkStreamCache;

    private DahuaSdkStreamService dahuaSdkStream() {
        DahuaSdkStreamService s = dahuaSdkStreamCache;
        if (s == null) {
            s = dahuaSdkStreamProvider.getIfAvailable();
            dahuaSdkStreamCache = s;
        }
        return s;
    }

    // ==================== 点播 ====================

    /**
     * 开始实时直播（标准 9.2）。
     *
     * @param deviceId  设备编码（SIP 目标）
     * @param channelId 通道编码（= ZLM 流名）；{@code null} 或与设备编码相同表示设备自身
     */
    public GbStream startLive(String deviceId, String channelId) {
        return startLive(deviceId, channelId, null);
    }

    /**
     * 平台主动点播。
     *
     * @param streamNumber 码流编号（GB/T 28181—2022 附录 G：SSRC 后 4 位）；
     *                     {@code null} 表示按设备配置的码流索引取值
     */
    public GbStream startLive(String deviceId, String channelId, Integer streamNumber) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;

        /*
         * SDK 直连设备（大华等）没有 SIP 点播：它不接 INVITE、不会向平台推 RTP。
         * 以前这里不做区分，一律走 platform.mediaService().play(...)，
         * 结果在编码校验那一步就抛「非法的 20 位设备编码: 10001-1」——
         * 因为大华的通道号 {@code 10001-1} 本来就不是国标 20 位编码。
         * 这类设备必须由平台给出 RTSP 地址、让 ZLM 反向去拉（见 startSdkLive）。
         */
        DeviceKind kind = kindOf(deviceId);
        if (kind != DeviceKind.GB28181) {
            return startSdkLive(deviceId, channel, kind, streamNumber);
        }

        RtpSession existing = platform.sessionManager().getByChannel(deviceId, channel);
        if (existing != null && !streamMatches(deviceId, channel, streamNumber)) {
            /*
             * 用户换了码流：SSRC 后 4 位在 INVITE 时就定死了，改不了，
             * 必须停掉旧会话重新点播。不停的话复用分支会把上一路码流的会话直接返回，
             * 界面表现为「切了子码流但画面/带宽完全没变」，而且没有任何报错。
             */
            log.info("码流切换，重建点播会话: channel={} {} -> {}", channel,
                    requestedStreams.get(streamKey(deviceId, channel)), streamNumber);
            stop(deviceId, channel);
            existing = platform.sessionManager().getByChannel(deviceId, channel);
        }
        if (existing != null && isReusable(existing)) {
            // 已有点播：直接复用，避免重复 INVITE 造成设备侧抖动
            return toStream(existing, GbStream.TYPE_LIVE);
        }
        GbChannel ch = deviceService.findChannel(deviceId, channel);
        if (ch != null && !ch.isOnline()) {
            throw BizException.badRequest("通道不在线：" + channel);
        }
        // 无活会话但设备侧可能仍在推上一轮的流（下级平台收 BYE 不停止）。
        // 此时直接发 INVITE 会让设备为同一通道开第二路推流 —— 实测 LiveGBS 每路约 3 Mbps，
        // 多点几次就攒成多路。先把已在推的那路认领回来。
        RtpSession adopted = platform.sessionManager().reclaimOrphanForChannel(deviceId, channel);
        if (adopted != null) {
            GbStream stream = toStream(adopted, GbStream.TYPE_LIVE);
            save(stream);
            deviceService.setChannelStream(deviceId, channel, stream.getStreamKey());
            return stream;
        }
        // 平台重启后 SIP 注册表为空（纯内存），设备要到下次心跳才自愈，
        // 空窗内点播会报「设备未注册或会话已失效」；按库里地址先补一次。
        healRegistryIfNeeded(ch);
        int resolvedStreamNumber = resolveStreamNumber(deviceId, streamNumber);
        RtpSession session = platform.mediaService().play(deviceId, channel, resolvedStreamNumber);
        // 记下本次用的码流，供下次复用判断（见 requestedStreams 注释）
        requestedStreams.put(streamKey(deviceId, channel), resolvedStreamNumber);
        GbStream stream = toStream(session, GbStream.TYPE_LIVE);
        save(stream);
        deviceService.setChannelStream(deviceId, channel, stream.getStreamKey());
        return stream;
    }

    /**
     * 已有点播会话是否就是用户这次要的码流。
     *
     * <p>没显式指定（{@code null}/{@code <0}，即「自动」）时一律视为匹配：
     * 「自动」不改变码流，不该因为用户重新点了同一个通道就拆会话。
     * 没有历史记录时也返回 {@code true} —— 那意味着会话是上次进程遗留的，
     * 按可复用处理比每次重建更安全（重建会多发一次 INVITE）。
     */
    private boolean streamMatches(String deviceId, String channel, Integer requested) {
        if (requested == null || requested < 0) {
            return true;
        }
        Integer last = requestedStreams.get(streamKey(deviceId, channel));
        return last == null || last.equals(requested);
    }

    /**
     * 决定本次点播用的码流编号：请求显式值 &gt; 设备配置的码流索引 &gt; 自动。
     * 「自动」返回 {@code -1}，由 SIP 层用域内自增序号填 SSRC 后 4 位（2016 行为）。
     */
    private int resolveStreamNumber(String deviceId, Integer requested) {
        if (requested != null && requested >= 0) {
            return requested;
        }
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            return -1;
        }
        return StreamIndex.of(device.getStreamIndex()).getStreamNumber();
    }

    /**
     * SDK 直连设备的码流编号。
     *
     * <p>与国标路径保持同一口径：<b>请求显式值 &gt; 设备配置的码流索引 &gt; 主码流</b>。
     *
     * <p>区别在于「自动」的落地：国标「自动」是 SSRC 后 4 位用域内自增序号（{@code -1}），
     * 而 RTSP 的 {@code subtype} 必须是确定值，所以「自动」回退到<b>主码流</b>（0）——
     * 与子码流相比，主码流清晰度优先，符合「没有明确要求时给最好的画面」。
     *
     * <p>早期这里写死 0，导致设备上配了「子码流」也不生效，
     * 弱网现场仍然拉主码流，白白占带宽。
     */
    private int resolveSdkStreamNumber(GbDevice device, Integer requested) {
        if (requested != null && requested >= 0) {
            return requested;
        }
        int configured = StreamIndex.of(device.getStreamIndex()).getStreamNumber();
        return configured < 0 ? 0 : configured;
    }

    /**
     * SDK 直连设备（大华等）的实时点播。
     *
     * <h3>两条路径，按「有没有 SDK 会话」自动选</h3>
     * <ol>
     *   <li><b>SDK 取流</b>（有会话时优先）—— SDK 拿国标 PS → 打 RTP → 灌 ZLM 的
     *       国标收流端口。走的是<b>已建立的登录会话</b>，方向与主动注册一致，
     *       天然穿透 NAT；ZLM 侧与国标设备完全同一条链路。</li>
     *   <li><b>RTSP 代理</b>（无会话时）—— 由 ZLM 反向拉设备的
     *       {@code rtsp://<ip>:554/...}。局域网可用，但设备在 NAT 后时 554 不可达
     *       （实测 554/37777/80 从公网全部连不上），会一直重试出不了图。</li>
     * </ol>
     * 之所以不再「一律走 RTSP」：那正是「主动注册成功了、设备也在线、
     * 但一点播就播不出来」的根因 —— 控制指令走通了主动注册连接，
     * 播放却在反向连设备的 554。
     */
    private GbStream startSdkLive(String deviceId, String channel,
                                  DeviceKind kind, Integer streamNumber) {
        GbChannel ch = deviceService.findChannel(deviceId, channel);
        if (ch != null && !ch.isOnline()) {
            throw BizException.badRequest("通道不在线：" + channel);
        }
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        // 有大华会话就走 SDK 取流（NAT 场景下这是唯一能出图的路径）
        DahuaSdkStreamService dahua = dahuaSdkStream();
        if (dahua != null && dahua.available(deviceId)) {
            return startDahuaSdkLive(device, channel, streamNumber);
        }

        DeviceAccessProvider provider = accessRegistry.require(kind);
        DeviceContext ctx = new DeviceContext(
                device.getDeviceId(), device.getRemoteIp(),
                device.getRemotePort() == null ? 0 : device.getRemotePort(),
                device.getUsername(), device.getPassword(),
                device.getChannelCount() == null ? 0 : device.getChannelCount());
        if (!ctx.connectable()) {
            throw BizException.badRequest("设备连接信息不完整（host/port/账号）：" + deviceId);
        }
        // 0 = 主码流；未指定时看设备配置的码流索引，与国标路径口径一致
        int sdkStream = resolveSdkStreamNumber(device, streamNumber);
        String url = provider.liveStreamUri(ctx, channel, sdkStream);
        if (url == null || url.isBlank()) {
            throw BizException.badRequest(
                    "设备未提供拉流地址（检查 dahua.rtsp-port 与通道号）：" + channel);
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        String app = deviceId;
        if (!media.startProxy(app, channel, url, false)) {
            throw BizException.badRequest("媒体服务拉流失败（检查设备 RTSP 是否开启）：" + url);
        }

        GbStream s = new GbStream();
        s.setStreamKey(deviceId + "/" + channel);
        s.setDeviceId(deviceId);
        s.setChannelId(channel);
        s.setStreamName(channel);
        s.setApp(app);
        s.setStreamType(GbStream.TYPE_LIVE);
        // 代理拉流没有 RTP 握手，ZLM 会立刻去连设备；状态先记 NEGOTIATING，
        // 由流上线事件改成 ONLINE（与国标路径同样的状态机）
        s.setState(RtpSession.NEGOTIATING);
        s.setTransport("TCP");
        s.setStartTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        PlayUrls urls = media.playUrls(app, channel);
        s.setHttpFlv(urls.getHttpFlv());
        s.setWsFlv(urls.getWsFlv());
        s.setHls(urls.getHls());
        s.setWebrtc(urls.getWebrtc());
        s.setRtsp(urls.getRtsp());
        s.setRtmp(urls.getRtmp());
        save(s);
        deviceService.setChannelStream(deviceId, channel, s.getStreamKey());
        log.info("SDK 设备点播已发起: {}/{} <- {}", app, channel, url.replaceAll("//[^@]*@", "//***@"));
        return s;
    }

    /**
     * 大华 SDK 取流：SDK 拿裸 H264/H265 → 直接喂 ZLM 建流。
     *
     * <p>与国标设备走<b>完全相同的下游链路</b>：ZLM 输出 FLV/HLS/WebRTC/RTSP，
     * app = 设备编码、stream = 通道编码。区别只在「流由谁喂」：
     * 国标是设备推 RTP，这里是平台把 SDK 裸流 {@code mk_media_input_h264} 进去。
     *
     * <p><b>没有 RtpSession</b>：所以状态不靠 {@code on_mk_media_publish} 回写，
     * 而是首帧推送成功即视为 ONLINE（{@code DahuaSdkStreamService.start} 已等到首帧）。
     */
    private GbStream startDahuaSdkLive(GbDevice device, String channel, Integer streamNumber) {
        String deviceId = device.getDeviceId();
        DahuaSdkStreamService dahua = dahuaSdkStream();
        if (dahua == null) {
            throw BizException.badRequest("大华取流服务未就绪");
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        DahuaSdkStreamService.LiveStream live = dahua.start(deviceId, channel);

        GbStream s = new GbStream();
        s.setStreamKey(deviceId + "/" + channel);
        s.setDeviceId(deviceId);
        s.setChannelId(channel);
        s.setStreamName(live.stream());
        s.setApp(live.app());
        s.setStreamType(GbStream.TYPE_LIVE);
        // 已拿到首帧（start 内部等过），流在 ZLM 里真实存在，可以直接标 ONLINE
        s.setState(RtpSession.ONLINE);
        s.setTransport("SDK");
        s.setStartTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        PlayUrls urls = media.playUrls(live.app(), live.stream());
        s.setHttpFlv(urls.getHttpFlv());
        s.setWsFlv(urls.getWsFlv());
        s.setHls(urls.getHls());
        s.setWebrtc(urls.getWebrtc());
        s.setRtsp(urls.getRtsp());
        s.setRtmp(urls.getRtmp());
        save(s);
        deviceService.setChannelStream(deviceId, channel, s.getStreamKey());
        log.info("大华 SDK 取流点播已发起: {}/{} 码流={}", deviceId, channel,
                streamNumber == null ? "auto" : streamNumber);
        return s;
    }

    /**
     * 判定设备的接入方式。
     *
     * <p>查不到设备记录时按国标处理：历史上所有设备都是国标，
     * 未知值一律保守归类（与 {@code DeviceKind.of} 的默认值一致）。
     */
    private DeviceKind kindOf(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return DeviceKind.GB28181;
        }
        GbDevice d = deviceService.findDevice(deviceId);
        return d == null ? DeviceKind.GB28181 : DeviceKind.of(d.getSource());
    }

    /**
     * SDK 直连设备的回放：由 ZLM 反向拉设备的 RTSP 回放流。
     *
     * <p>大华的 RTSP 回放地址与实时地址是**同一套语法但不同路径**：
     * <pre>
     *   实时  rtsp://user:pass@ip:554/cam/realmonitor?channel=1&subtype=0
     *   回放  rtsp://user:pass@ip:554/cam/playback?channel=1&subtype=0&starttime=2026_11_02_00_00_00&endtime=2026_11_02_01_00_00
     * </pre>
     * 注意时间是下划线分隔且**不能带 T 与冒号**；写成国标的 ISO 格式设备会直接 401/404。
     *
     * <p>回放流名带 {@code _playback} 后缀：与直播共存时两者是独立媒体源，
     * 共用流名会让后拉的那路覆盖前一路（实测表现为「点回放，实时画面被顶掉」）。
     */
    private GbStream startSdkPlayback(String deviceId, String channel, RecordItem item,
                                      DeviceKind kind) {
        if (item == null || item.getStartTime() == null || item.getEndTime() == null) {
            throw BizException.badRequest("回放需要录像段的起止时间");
        }
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DeviceAccessProvider provider = accessRegistry.require(kind);
        DeviceContext ctx = new DeviceContext(
                device.getDeviceId(), device.getRemoteIp(),
                device.getRemotePort() == null ? 0 : device.getRemotePort(),
                device.getUsername(), device.getPassword(),
                device.getChannelCount() == null ? 0 : device.getChannelCount());
        if (!ctx.connectable()) {
            throw BizException.badRequest("设备连接信息不完整（host/port/账号）：" + deviceId);
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String url = provider.playbackStreamUri(ctx, channel,
                sdf.format(item.getStartTime()), sdf.format(item.getEndTime()));
        if (url == null || url.isBlank()) {
            throw BizException.badRequest("设备未提供回放地址（检查时间格式与通道号）：" + channel);
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        String app = deviceId;
        String streamName = channel + "_playback";
        if (!media.startProxy(app, streamName, url, false)) {
            throw BizException.badRequest("媒体服务拉取回放流失败（检查设备是否开启 RTSP）：" + url);
        }

        GbStream s = new GbStream();
        s.setStreamKey(deviceId + "/" + streamName);
        s.setDeviceId(deviceId);
        s.setChannelId(channel);
        s.setStreamName(streamName);
        s.setApp(app);
        s.setStreamType(GbStream.TYPE_PLAYBACK);
        s.setState(RtpSession.NEGOTIATING);
        s.setTransport("TCP");
        s.setStartTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        PlayUrls urls = media.playUrls(app, streamName);
        s.setHttpFlv(urls.getHttpFlv());
        s.setWsFlv(urls.getWsFlv());
        s.setHls(urls.getHls());
        s.setWebrtc(urls.getWebrtc());
        s.setRtsp(urls.getRtsp());
        s.setRtmp(urls.getRtmp());
        save(s);
        log.info("SDK 设备回放已发起: {}/{} <- {}", app, streamName,
                url.replaceAll("//[^@]*@", "//***@"));
        return s;
    }

    /**
     * 既有会话能否直接复用给新的点播请求，三条缺一不可：
     * 类型必须是直播（键上可能残留历史会话，实测复用后返回的 {@code filePath} 指向录像文件）；
     * 状态不能是 CLOSED；流必须真的在推（流下线只置 NEGOTIATING 不置 CLOSED，
     * 复用会拿到播不出画面的死会话，需问 ZLM 是否真的在收 RTP）。
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
     * 取该通道当前的实时会话（没有则返回 {@code null}），供云端录像保活判断是否需再点播。
     *
     * @return 会话；不存在或已 CLOSED 时返回 {@code null}
     */
    public RtpSession liveSession(String deviceId, String channelId) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        RtpSession s = platform.sessionManager().getByChannel(deviceId, channel);
        return s == null || RtpSession.CLOSED.equals(s.getState()) ? null : s;
    }

    /**
     * 开始实时直播（设备直连：设备编码即通道编码）。下挂子通道请用 {@link #startLive(String, String)}。
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
     * 必须与直播隔离：两者会话键与 ZLM 流名都带 {@code _playback} 后缀，否则回放会走
     * {@code open()} 的「先关闭旧会话」分支把正在看的直播踢掉（实测日志：
     * {@code 已有会话，先关闭旧会话: type=REALTIME, state=ONLINE} 后画面直接中断）。
     * 同一通道重复回放复用已在播的会话，避免重复 INVITE 造成设备抖动与重复推流。
     */
    public GbStream startPlayback(String deviceId, String channelId, RecordItem item) {
        return startPlayback(deviceId, channelId, item, 1);
    }

    /**
     * 开始历史回放，可指定下载倍速。
     * 倍速走回放：标准 9.9 的 {@code s=Download} 设备支持度很差，本平台下载复用回放链路 + 本地录制，
     * 而 9.9.1 b) 规定倍速由 SDP 的 {@code a=downloadspeed:N} 携带，与 {@code s=} 无关。
     * 倍速大于 1 时不能复用会话：推流速率由上一次 INVITE 的 SDP 决定，
     * 复用会让 8 倍速静默退化成 1 倍速。
     *
     * @param downloadSpeed 倍速档位（设备 {@code Info/DownloadSpeed} 声明的值）；{@code 1} 表示实时速度
     */
    public GbStream startPlayback(String deviceId, String channelId, RecordItem item,
                                  int downloadSpeed) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;

        /*
         * SDK 直连设备（大华等）的回放同样是「让 ZLM 去拉」而不是「让设备推」。
         * 不在这里分流的话会一路走到 platform.mediaService().playback(...)，
         * 在国标编码校验处抛「非法的 20 位设备编码」——
         * 与实时点播踩的是同一个坑，只是回放这条更晚才发现。
         */
        DeviceKind playbackKind = kindOf(deviceId);
        if (playbackKind != DeviceKind.GB28181) {
            return startSdkPlayback(deviceId, channel, item, playbackKind);
        }

        RtpSession existing = platform.sessionManager()
                .getByChannel(deviceId, channel, MediaType.PLAYBACK);
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
            /*
             * 判断「是不是同一段录像」必须比较会话身份（mediaUri）而非 filePath：
             * 大量设备（实测 LiveGBS、多数国产摄像机）不提供 FilePath，只按时间段管理录像。
             * 无条件复用会让点不同时间段都命中同一个卡在 NEGOTIATING 的会话，界面「换时间点播毫无反应」。
             */
            String want = recordIdentity(item);
            String have = existing.getMediaUri();
            boolean same = want != null && want.equals(have);
            /*
             * 只有正在推流的会话才值得复用：设备停推后会话仍在（状态 OFFLINE），但 ZLM 媒体源已被摘除，
             * 复用这个僵尸会话会让播放器永远拿不到数据。
             * 典型触发：暂停超过 ZLM 的 RTP 超时（{@code RtpProcess timeout}，约 15~20 秒）后再点继续。
             */
            if (same && downloadSpeed <= 1 && RtpSession.ONLINE.equals(existing.getState())) {
                return toStream(existing, GbStream.TYPE_PLAYBACK);
            }
            /*
             * 走到这里只剩两种意图：换一段录像，或同一段要求更高倍速，两者都必须销毁旧会话，
             * 所以下载锁检查放在共同入口 —— 提速那条路同样会 stop 掉下载中的会话。
             * 键必须取自 {@code existing.getSessionKey()} 而不是自己拼：真实规则在
             * {@code RtpSessionManager#sessionKey}（编码缺失时 app/stream 会互相回退），
             * 拼错的锁永远锁不住，比没有锁更糟。
             */
            if (isDownloadLocked(existing.getSessionKey())) {
                throw BizException.badRequest(
                        "该通道正在后台下载，无法同时回放或倍速播放；"
                                + "请等下载完成，或先在「下载任务」里取消");
            }
            if (same) {
                log.info("回放需提速到 {}x，重建会话: {} {}", downloadSpeed, deviceId, channel);
            } else {
                /*
                 * 请求的是另一段录像 → 显式停掉旧会话。
                 * 不显式停会让 {@code sessionManager.open} 用新会话顶掉旧的，
                 * 旧会话对应的媒体源与录制随之重置，正在跑的下载只得到开头几十秒（实测）。
                 */
                log.info("回放切换录像段，关闭旧会话: {} {} → {}", deviceId, channel, want);
            }
            stop(deviceId, channel, MediaType.PLAYBACK);
        }
        RtpSession session = platform.mediaService()
                .playback(deviceId, channel, item, downloadSpeed);
        GbStream stream = toStream(session, GbStream.TYPE_PLAYBACK);
        save(stream);
        return stream;
    }

    /**
     * 被后台下载占用的回放会话键。
     * 下载与回放共用一路回放流，而回放会话同通道只有一个：允许另开回放会把会话顶掉、下载断流，
     * 产物只有开头一小段（这是「下载时长不够」最主要的原因），所以从源头拒绝。
     */
    private final java.util.Set<String> downloadLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 各会话「最近一次确认有观众」的时刻（毫秒）。
     * {@code readerCount()} 是瞬时值：播放器重连的几百毫秒间隙、SourceBuffer 写入阻塞、
     * ZLM 多协议源瞬时不一致都会读到 0，只看一次采样就会把正在看的流关掉（前端反复重连、重新缓冲）。
     * 因此只在读者数 &gt; 0 时记时间戳，连续 {@code grace} 毫秒都看不到观众才关。
     */
    private final java.util.Map<String, Long> lastViewerAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 忘记某会话的观看记录（会话关闭/重建时调用）。 */
    public void forgetViewers(String sessionKey) {
        if (sessionKey != null) {
            lastViewerAt.remove(sessionKey);
        }
    }

    /** 标记该会话正被下载占用（重复调用无副作用）。 */
    public void lockForDownload(String sessionKey) {
        if (sessionKey != null && !sessionKey.isBlank()) {
            downloadLocks.add(sessionKey);
        }
    }

    /** 解除下载占用（下载收尾/取消/失败都要调用，否则该通道回放会被长期拒之门外）。 */
    public void unlockDownload(String sessionKey) {
        if (sessionKey != null) {
            downloadLocks.remove(sessionKey);
        }
    }

    /** 该会话是否正被下载占用。 */
    public boolean isDownloadLocked(String sessionKey) {
        return sessionKey != null && downloadLocks.contains(sessionKey);
    }

    /** 开始历史回放（设备直连：设备编码即通道编码）。 */
    public GbStream startPlayback(String deviceId, RecordItem item) {
        return startPlayback(deviceId, deviceId, item);
    }

    /**
     * 录像段的「会话身份」：有 FilePath 用它，否则用起止时间。
     * 必须与 {@code Gb28181MediaService#playback} 写 {@code mediaUri} 的规则完全一致，
     * 否则复用判断永远不成立、每点一次都重开会话。
     */
    private static String recordIdentity(RecordItem item) {
        if (item == null) {
            return null;
        }
        if (item.getFilePath() != null && !item.getFilePath().isBlank()) {
            return item.getFilePath();
        }
        String start = RecordItem.formatTime(item.getStartTime());
        if (start == null) {
            return null;
        }
        String end = RecordItem.formatTime(item.getEndTime());
        return start + "/" + (end == null ? start : end);
    }

    /**
     * 停止某通道的流（发 BYE 释放设备带宽 + 关闭收流端口）。
     * BYE 与本地释放统一由 {@link Gb28181MediaService#stop(String, String)} 负责，避免两条停止路径
     * 行为不一致（早先 {@code /api/stop} 只释放本地资源不发 BYE，设备会继续推流）。
     * 库状态由 {@code StreamEventBridge.onSessionClosed} 回写，此处不重复处理。
     *
     * @return 是否找到并关闭了会话
     */
    public boolean stop(String deviceId, String channelId) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        /*
         * SDK 直连设备没有 RTP 会话，走不到 mediaService().stop()。
         * 不单独处理的话，停止点播会「接口回成功但流一直在」——
         * ZLM 的代理玩家还拉着设备，设备侧多一路 RTSP 连接，直到进程退出。
         */
        if (kindOf(deviceId) != DeviceKind.GB28181) {
            ZlmMediaServer media = platform.mediaServer();
            if (media == null) {
                return false;
            }
            /*
             * 两条 SDK 路径都要收：
             *   · RTSP 代理   → stopProxy + closeMediaSource
             *   · SDK 取流    → DahuaSdkStreamService.stop（它会自己释放媒体源）
             * 只收其中一条会留下「接口回成功但设备还在被取流」。
             */
            DahuaSdkStreamService dahua = dahuaSdkStream();
            if (dahua != null) {
                dahua.stop(deviceId + "/" + channel);
            }
            media.stopProxy(deviceId, channel);
            media.closeMediaSource(deviceId, channel);
            requestedStreams.remove(streamKey(deviceId, channel));
            markClosed(deviceId + "/" + channel);
            /*
             * 必须显式清通道的 stream_id：它是通道列表判断「有流 / 显示停止按钮」的依据。
             * 只 markClosed 清的是 gb_stream，通道表那条记录还在，
             * 表现为「点了停止、流列表空了，通道列表仍然显示在推流」。
             */
            deviceService.setChannelStream(deviceId, channel, null);
            return true;
        }
        requestedStreams.remove(streamKey(deviceId, channel));
        return platform.mediaService().stop(deviceId, channelId);
    }

    /** 停止某通道的流（设备直连：设备编码即通道编码）。 */
    public boolean stop(String deviceId) {
        return stop(deviceId, deviceId);
    }

    /**
     * 停止某通道指定类型的流：回放 / 下载与直播是独立会话（键带类型后缀），
     * 停回放必须走本方法，否则会关掉直播会话或关不到任何会话。
     *
     * @param type {@code null} 或 {@link MediaType#REALTIME} 等价于 {@link #stop(String, String)}
     */
    public boolean stop(String deviceId, String channelId, MediaType type) {
        return platform.mediaService().stop(deviceId, channelId, type);
    }

    /**
     * 某通道的回放会话。
     * 必须按类型查：同一通道通常同时有直播（云端录像保活让它长期在线）与回放两路会话，
     * 按「设备 + 通道」查只能拿到直播，于是定位 / 暂停 / 倍速会发到直播对话框里，
     * 设备回 200 却不执行，严重的还会把直播打断（「拖动像重新播流、倍速没用」的根因）。
     *
     * @return 回放会话；该通道没有回放时返回 {@code null}
     */
    public RtpSession playbackSession(String deviceId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        RtpSession session = platform.sessionManager()
                .getByChannel(deviceId == null ? channelId : deviceId, channelId, MediaType.PLAYBACK);
        if (session != null) {
            return session;
        }
        /* 兜底：按通道编码找回放类型的会话。只认 PLAYBACK，绝不能退化成任意类型（那就又抓回直播了）。 */
        for (RtpSession s : sessions()) {
            if (channelId.equals(s.getChannelId())
                    && s.getType() == MediaType.PLAYBACK
                    && !RtpSession.CLOSED.equals(s.getState())) {
                return s;
            }
        }
        return null;
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
     * 通道编码跨设备可能重复，多设备环境请用 {@link #session(String, String)}。
     */
    public RtpSession session(String channelId) {
        return platform.sessionManager().getByChannel(channelId);
    }

    /** 按会话键（{@code <设备编码>/<通道编码>}，与 ZLM 的 {@code app/stream} 一致）取会话。 */
    public RtpSession sessionByKey(String sessionKey) {
        return platform.sessionManager().getByKey(sessionKey);
    }

    /**
     * 按任意一种外部标识定位会话，顺序不能变：
     * 会话键 {@code <设备编码>/<通道编码>}（列表 / 启动接口返回的形态）&gt;
     * 裸通道编码（直连摄像机）&gt; ZLM 流名（早期版本返回过，外部脚本仍在用）。
     * 三种形态集中在一处：原本 StreamController / PlaybackController / DecodeController
     * 各有一份拷贝，分别只认其中几种，导致拿列表接口返回的 {@code streamId} 回查恒 404。
     *
     * @return 会话；找不到或入参为空时返回 {@code null}
     */
    public RtpSession findSession(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return null;
        }
        RtpSession session = sessionByKey(streamId);
        if (session != null) {
            return session;
        }
        session = session(streamId);
        if (session != null) {
            return session;
        }
        for (RtpSession s : sessions()) {
            if (streamId.equals(s.getStream())) {
                return s;
            }
        }
        return null;
    }

    /** 录像服务（ZLM 录制器）；未启用时返回 {@code null}。 */
    public io.github.rurulalanana.gb28181.sip.zlm.RecordingService recordingService() {
        return platform.recordingService();
    }

    /**
     * ZLM 是否已真正收到该会话的 RTP。
     * 「会话存在」不等于「媒体源就绪」：会话在 INVITE 发出后立刻创建，
     * ZLM 要等第一个 RTP 包才注册媒体源，录制 / 抓拍必须用本方法判就绪。
     */
    public boolean isReceiving(String sessionKey) {
        RtpSession session = platform.sessionManager().getByKey(sessionKey);
        if (session == null) {
            return false;
        }
        var mediaServer = platform.mediaServer();
        if (mediaServer == null) {
            return false;
        }
        // 用 ZLM 实际坐标探测：单端口模式下 app/stream 与业务坐标不同
        return mediaServer.isRtpReceiving(session.getZlmApp(), session.getStream());
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
        /* 观众数（ZLM 读者计数，含录制器）。ZLM 对 ws-flv 的计数准确（实测连着 1、断开 0），是「有没有人看」的权威来源。 */
        if (platform.mediaServer() != null) {
            vo.setReaders(platform.mediaServer()
                    .readerCount(session.getZlmApp(), session.getStream()));
        }
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
     * 插入或更新流记录。{@code stream_key} 有唯一约束，而 ZLM 的流注册事件可能重复投递，
     * 并发下「先查再插」会撞唯一键，因此捕获冲突后改为按 stream_key 更新，保证幂等。
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

    /** 流上线。{@code streamKey} 为复合流标识 {@code <设备编码>/<通道编码>}。 */
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
     * 标记流已关闭。{@code streamKey} 为复合流标识 {@code <设备编码>/<通道编码>}。
     *
     * @return 本次是否真的改动了状态（已是 CLOSED 或记录不存在时返回 {@code false}）
     */
    public boolean markClosed(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return false;
        }
        GbStream record = findByStreamKey(streamKey);
        if (record != null && !GbStream.STATE_CLOSED.equals(record.getState())) {
            record.setState(GbStream.STATE_CLOSED);
            record.setUpdateTime(LocalDateTime.now());
            streamMapper.updateById(record);
            return true;
        }
        return false;
    }

    /**
     * 该流是否由平台通过厂商 SDK 直接取流产生（{@code transport=SDK}）。
     *
     * <p><b>为什么需要区分</b>：国标流的「下线」是暂时的 —— 设备可能重启、网络可能抖动，
     * 端口与会话都保留着等它恢复，所以 {@code markOffline} 把它标成 NEGOTIATING 是对的。
     * <b>SDK 流没有这个「等恢复」的过程</b>：平台一旦关掉发布器，ZLM 里的媒体源就没了，
     * 只有下次点播才会重建。若也标 NEGOTIATING，就会留下一条永不收敛的僵尸记录
     * （{@code liveRowsNotIn} 按「非 CLOSED」捞它 {@code /stream/list}），
     * 表现为「推流数一直是 1，但播放器打不开、读者数恒为 0」。
     */
    public boolean isSdkStream(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return false;
        }
        GbStream record = findByStreamKey(streamKey);
        return record != null && TRANSPORT_SDK.equalsIgnoreCase(record.getTransport());
    }

    /** SDK 取流的 transport 标记（{@code gb_stream.transport}）。 */
    public static final String TRANSPORT_SDK = "SDK";

    /**
     * 该通道当前是否真的有一路直播流在线。
     *
     * <h3>为什么不能只看 RtpSession</h3>
     * 国标流有 {@code RtpSession}，但 <b>SDK 直连设备（大华等）与 RTSP 代理都没有</b> ——
     * 它们的流由平台自己的发布器/代理维持，会话表里查不到。
     *
     * <p>踩过的坑：WebRTC 控制器用 {@code sessionManager().getByChannel(...) != null}
     * 判断「流是否已存在」，对 SDK 流恒为 {@code false}，于是它每次都以为流没建好，
     * 又调一次 {@code startLive} → {@code DahuaSdkStreamService.start} 开头先 {@code stop(key)}
     * 把刚建好的流拆掉 → 紧接着重建时设备侧还没释放，报
     * {@code 0x8000040A 创建套接字失败}。表现为「点播成功了一瞬间又失败」。
     *
     * <h3>三层判据</h3>
     * <ol>
     *   <li>国标：内存里 RTP 会话存在；</li>
     *   <li>SDK 取流：发布器还在跑（内存 map，最准，不受库里残留记录影响）；</li>
     *   <li>RTSP 代理等：库里非 CLOSED 且 ZLM 里确实还有视频轨
     *       （只看库会被僵尸记录骗，只看 ZLM 会把「刚建流、轨道还没就绪」误判成没有）。</li>
     * </ol>
     */
    public boolean hasLiveStream(String deviceId, String channelId) {
        if (deviceId == null || deviceId.isBlank()
                || channelId == null || channelId.isBlank()) {
            return false;
        }
        RtpSessionManager sessions = platform.sessionManager();
        if (sessions != null && sessions.getByChannel(deviceId, channelId) != null) {
            return true;
        }
        DahuaSdkStreamService dahua = dahuaSdkStream();
        if (dahua != null && dahua.isRunning(deviceId, channelId)) {
            return true;
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            return false;
        }
        try {
            GbStream row = findByChannel(deviceId, channelId, GbStream.TYPE_LIVE);
            return row != null && !GbStream.STATE_CLOSED.equals(row.getState())
                    && media.videoCodecName(row.getApp(), row.getStreamName()) != null;
        } catch (Exception e) {
            log.debug("判断直播流是否存在失败（按不存在处理）: {}/{} {}", deviceId, channelId, e.toString());
            return false;
        }
    }

    /**
     * 平台启动时把库里残留的「非 CLOSED」流记录收敛为 CLOSED。
     * 会话是纯内存对象，重启即全没，而库里还留着 {@code ONLINE} / {@code NEGOTIATING}，
     * 界面会显示「推流中」但实际一路都没有；重启后内存为空是既成事实，统一置 CLOSED 是准确的。
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
            // 先补发 BYE 再改状态：这些对话在设备/下级平台侧可能还「活着」。
            releaseStaleRemoteSessions(stale);
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

    /** 单次启动最多补发多少个 BYE（防止异常数据导致刷屏）。 */
    private static final int MAX_STALE_RELEASE = 50;

    /**
     * 为上一轮运行遗留的对话补发 BYE，释放设备侧仍在进行的推流。
     * 进程被强杀时来不及发 BYE，设备 / 下级平台会一直推 —— 实测 LiveGBS 每路约 3 Mbps 且从不超时，
     * 多重启几次就攒成多路重复推流。平台侧已无对话信息，只能靠库里持久化的 Call-ID 补发；
     * 实测 LiveGBS 按 Call-ID 匹配、不带原 tag 也回 200 OK，严格实现回 481 无害。
     *
     * @return 成功发出的 BYE 数
     */
    private int releaseStaleRemoteSessions(List<GbStream> stale) {
        Gb28181MediaService media = platform.mediaService();
        if (media == null) {
            return 0;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        int sent = 0;
        int skipped = 0;
        for (GbStream s : stale) {
            String callId = s.getCallId();
            if (callId == null || callId.isBlank() || !seen.add(callId)) {
                continue;
            }
            if (seen.size() > MAX_STALE_RELEASE) {
                skipped++;
                continue;
            }
            try {
                healRegistryForRelease(s.getDeviceId());
                if (media.byeQuietly(s.getDeviceId(), s.getChannelId(), callId)) {
                    sent++;
                }
            } catch (Exception e) {
                log.debug("补发 BYE 失败: callId={} {}", callId, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("已为 {} 个上一轮遗留对话补发 BYE（释放设备侧仍在进行的推流）", sent);
        }
        if (skipped > 0) {
            log.warn("遗留对话过多，跳过 {} 个未补发 BYE", skipped);
        }
        return sent;
    }

    /**
     * 补发 BYE 前先把设备的 SIP 会话补回来。
     * 平台刚启动时注册表为空（纯内存），实测启动瞬间补发直接报「设备未注册或会话已失效」，
     * 回收动作全部失效；用库里存的 {@code remote_ip/remote_port} 先建会话即可。
     */
    private void healRegistryForRelease(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || registryHealer.inRegistry(deviceId)) {
            return;
        }
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            return;
        }
        registryHealer.healFromDb(deviceId, device.getRemoteIp(), device.getRemotePort(),
                device.getTransport());
    }

    /**
     * 把「设备仍在推、平台却没有会话」的孤儿流重新纳管。
     * {@code on_mk_media_publish} 只在流首次注册时触发一次，而设备（实测 LiveGBS）收 BYE 回 200 却不停止推流，
     * 平台启动时又存在「回调先到、SSRC 映射后恢复」的竞争（实测相差 1.4 秒），这路流就永久停在未匹配状态。
     * 重建后 ZLM 侧流名不变（{@code stream_replace} 只在注册时生效），但平台已有会话、能出播放地址。
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
                // 认领后的会话其 zlmStream 正是这个十六进制名（stream_replace 改不动已注册的流），
                // 所以「流名是十六进制」不再等价于「未纳管」，不排除会每轮重复报「已重新纳管」。
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
     * 空闲流巡检：关掉没有任何观看者的会话。
     * 不能只靠 ZLM 的 {@code on_mk_media_no_reader}：它每个媒体源只触发一次，且只在读者数由非零降为零时触发。
     * 平台重启后设备仍在推流时，回调在会话重建前就触发过（实测相差 2 秒）；
     * {@link #adoptOrphanStreams()} 纳管时流早已注册，回调也不会补发 —— 这些流会一直占带宽与端口。
     * 判据用 ZLM 读者数：它把 MP4 录制器也算作读者（{@code protocol.mp4_as_player=1}），「只录不看」不会被误关。
     * 宽限期必须由调用方传入运行时配置值：读 {@code noneReaderDelayMs()} 拿到的是启动时 yml 快照，
     * 用户热更新后界面看起来「改了没生效」。
     *
     * @param graceMs 宽限期（毫秒）；{@code <= 0} 时回落到启动配置值
     * @return 关闭的会话数
     */
    public int reapIdleStreams(long graceMs) {
        ZlmMediaServer mediaServer = platform.mediaServer();
        RtpSessionManager sessions = platform.sessionManager();
        if (mediaServer == null || sessions == null) {
            return 0;
        }
        // 20 秒下限：浏览器从点播到真正建立拉流要 1~3 秒，再快就会误杀刚打开的页面
        long grace = graceMs > 0 ? graceMs : mediaServer.noneReaderDelayMs();
        grace = Math.max(20000L, grace);
        long now = System.currentTimeMillis();
        // 一次性取出「要求常开推流」的通道，避免在循环里逐个查库。
        // 每 20 秒跑一次 × 通道数 次查询是纯浪费，一次全量反而更划算。
        java.util.Set<String> keepAlive = channelsRequiringStream();
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
                if (now - session.getCreatedAt() < grace) {
                    continue;
                }
                // 必须尊重通道的「常开」设置：原本只按 ZLM 读者数判断，等于「所有通道都按需」，
                // 用户关掉 onDemand（期望常开）也照样被关流，表现为「设了常开还是过一会儿就断」。
                if (keepAlive.contains(session.getChannelId())) {
                    continue;
                }
                int readers = mediaServer.readerCount(session.getZlmApp(), session.getStream());
                /*
                 * 诊断日志（仅 DEBUG）：排查「没人看却一直不回收」时需看到每路真实读者数，
                 * 以区分「确实有播放器连着」与「读者数恒为 0 但判定逻辑有 bug」。
                 */
                if (log.isDebugEnabled()) {
                    long seenAgo = now - lastViewerAt.getOrDefault(
                            session.getSessionKey(), session.getCreatedAt());
                    log.debug("空闲巡检采样: {} readers={} 距上次见观众={}ms 宽限={}ms 常开={} 明细={}",
                            session.getSessionKey(), readers, seenAgo, grace,
                            keepAlive.contains(session.getChannelId()),
                            mediaServer.readerDetail(session.getZlmApp(), session.getStream()));
                }
                if (readers > 0) {
                    // 看到观众就刷新时间戳：本次不关，且给下一次采样留出宽限
                    lastViewerAt.put(session.getSessionKey(), now);
                    continue;
                }
                /*
                 * 读者数为 0 不能据此立刻关流：这是瞬时采样，实测会让用户正在看时被判「无观看者」并关流，
                 * 前端 WebSocket 断开 → 重连 → 重新缓冲。只在连续 {@code grace} 毫秒都没见过观众时才真关。
                 */
                long lastSeen = lastViewerAt.getOrDefault(session.getSessionKey(), session.getCreatedAt());
                if (now - lastSeen < grace) {
                    continue;
                }
                log.info("空闲流巡检：{} 秒内无观看者，关闭会话 {}", grace / 1000, session.getSessionKey());
                // 先记冷却再关流：设备不认 BYE 时会继续推，不记冷却会被孤儿流巡检立刻认领回来。
                // 按会话键记而不是 SSRC：会话的 ssrc 是 SDP 通告值，与设备实际推流的 SSRC 不同（实测 11 秒后又被认领）。
                sessions.markReaped(session.getSessionKey());
                sessions.closeByKey(session.getSessionKey());
                // 会话已销毁：观看记录一并清掉，避免键位长期堆积
                lastViewerAt.remove(session.getSessionKey());
                closed++;
            } catch (Exception e) {
                log.debug("关闭空闲流失败: {}", e.getMessage());
            }
        }
        return closed;
    }

    /**
     * 找出「要求流常开、不能被空闲回收」的通道编码集合：
     * 应录云端录像（用户开关 ∪ 录像计划，判定走 {@link ChannelRecordPolicy#enabledChannels()}）
     * 与用户显式设成常开（{@code on_demand=false}）两类。
     * 录像判定必须走 recordPolicy：录像计划不写库，只读库会让计划刚开录的流被回收。
     * 一次全量查库优于循环里 N 次单条查询（每 20 秒调用一次）；查库失败返回空集合，退化为全部按需。
     */
    private java.util.Set<String> channelsRequiringStream() {
        try {
            java.util.Set<String> out = new java.util.HashSet<>();
            // 1. 应录通道（用户开关 ∪ 录像计划，纯内存，不查库）
            if (recordPolicy != null && recordPolicy.isLoaded()) {
                out.addAll(recordPolicy.enabledChannels());
            }
            // 2. 用户显式设成常开的通道（读库）
            for (GbChannel ch : deviceService.allChannels()) {
                if (ch.getChannelId() == null) {
                    continue;
                }
                if (Boolean.FALSE.equals(ch.getOnDemand())) {
                    out.add(ch.getChannelId());
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("读取常开通道失败（本次按全部按需处理）: {}", e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * 按类型查某通道的当前流记录。用「设备编码 + 通道编码」精确定位：
     * 通道编码跨设备可重复，只按通道编码查会拿到别的设备的同号通道记录。
     *
     * @param deviceId 设备编码；{@code null} 时退化为只按通道编码查
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
     * 按类型查某通道的当前流记录（设备直连，多设备环境请用
     * {@link #findByChannel(String, String, String)}）。
     */
    public GbStream findByChannel(String channelId, String type) {
        return findByChannel(null, channelId, type);
    }

    /** 按流名查记录。 */
    public GbStream findByStreamKey(String streamKey) {
        return streamMapper.selectOne(Wrappers.<GbStream>lambdaQuery()
                .eq(GbStream::getStreamKey, streamKey).last("limit 1"));
    }

    /**
     * 按流名取返回体，供 {@code /stream/info} 在「没有 RtpSession」时回落。
     *
     * <p>SDK 直连设备的流（RTSP 代理 / SDK 取流）不产生 RtpSession，
     * 只按会话查会让这类流永远报「直播流不存在」。
     *
     * @return 不存在或已关闭时返回 {@code null}
     */
    public StreamVo describeByStreamKey(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return null;
        }
        GbStream row = findByStreamKey(streamKey);
        if (row == null || GbStream.STATE_CLOSED.equals(row.getState())) {
            return null;
        }
        return describe(row);
    }

    /**
     * 直播流列表。
     *
     * <p>国标流来自内存会话（保证「刚点播未落库」也能查到）；
     * <b>SDK 直连设备的流没有 RtpSession</b>（RTSP 代理与 SDK 取流都是），
     * 只取会话会漏掉它们，界面上「当前推流」永远是 0。因此再把库里
     * 未关闭的直播记录补进来，按 {@code streamKey} 去重。
     */
    public List<StreamVo> liveList() {
        List<StreamVo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (RtpSession s : sessions()) {
            if (s.getType() == MediaType.REALTIME) {
                StreamVo vo = describe(s);
                seen.add(vo.getStreamId());
                out.add(vo);
            }
        }
        for (GbStream row : liveRowsNotIn(seen)) {
            out.add(describe(row));
        }
        return out;
    }

    /**
     * 库里未关闭、且不在给定集合里的直播记录（SDK 流就靠它进列表）。
     *
     * <h3>僵尸记录防御</h3>
     * 早期 SDK 流结束时不落 CLOSED（见 {@link StreamEventBridge} 里
     * {@code onStreamOffline} 的注释），库里会留下一条 {@code NEGOTIATING} 的记录，
     * 这条查询每次都把它捞出来 → 界面「推流数恒为 1」但播放器打不开。
     *
     * <p>判据用 ZLM 里是否还有视频轨：SDK 流的媒体源由平台自己的发布器维持，
     * 发布器关了轨道就没了，比任何时间戳都准。命中即顺手标 CLOSED 自愈，
     * 下次查询就不会再扫到它（每条僵尸只会被修正一次）。
     *
     * <p>只对 {@code transport=SDK} 生效：国标流的 {@code NEGOTIATING} 是「等设备恢复」的
     * 正常中间态，此时 ZLM 里本来就可能还没有轨道，不能当僵尸处理。
     */
    private List<GbStream> liveRowsNotIn(java.util.Set<String> skip) {
        try {
            List<GbStream> rows = streamMapper.selectList(Wrappers.<GbStream>lambdaQuery()
                    .eq(GbStream::getStreamType, GbStream.TYPE_LIVE)
                    .ne(GbStream::getState, GbStream.STATE_CLOSED)
                    .orderByDesc(GbStream::getStartTime));
            ZlmMediaServer media = platform.mediaServer();
            List<GbStream> out = new ArrayList<>();
            for (GbStream r : rows) {
                if (skip.contains(r.getStreamKey())) {
                    continue;
                }
                if (TRANSPORT_SDK.equalsIgnoreCase(r.getTransport()) && media != null
                        && media.videoCodecName(r.getApp(), r.getStreamName()) == null) {
                    r.setState(GbStream.STATE_CLOSED);
                    r.setUpdateTime(LocalDateTime.now());
                    try {
                        streamMapper.updateById(r);
                    } catch (Exception ignore) {
                        // 自愈失败不影响本次返回，下次查询会再试
                    }
                    log.info("收敛僵尸 SDK 流记录（ZLM 已无视频轨）: {}", r.getStreamKey());
                    continue;
                }
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            log.debug("补充 SDK 流列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 库里的流记录 → 返回体（SDK 直连设备用）。
     *
     * <p>与 {@link #describe(RtpSession)} 的差别：没有 SSRC/RTP 端口，
     * 播放地址按 {@code app/stream} 现算；观众数仍走 ZLM 读者计数。
     */
    private StreamVo describe(GbStream row) {
        StreamVo vo = new StreamVo();
        vo.setStreamId(row.getStreamKey());
        vo.setDeviceId(row.getDeviceId());
        vo.setChannelId(row.getChannelId());
        vo.setSmsId(props.getPlatformId());
        vo.setState(row.getState());
        vo.setTransport(row.getTransport());
        ZlmMediaServer media = platform.mediaServer();
        if (media != null && row.getApp() != null && row.getStreamName() != null) {
            PlayUrls urls = media.playUrls(row.getApp(), row.getStreamName());
            vo.setWebrtc(urls.getWebrtc());
            vo.setFlv(urls.getHttpFlv());
            vo.setWsFlv(urls.getWsFlv());
            vo.setHls(urls.getHls());
            vo.setRtsp(urls.getRtsp());
            vo.setRtmp(urls.getRtmp());
            vo.setReaders(media.readerCount(row.getApp(), row.getStreamName()));
        }
        GbChannel channel = deviceService.findChannel(row.getDeviceId(), row.getChannelId());
        if (channel != null) {
            vo.setChannelName(channel.displayName());
            vo.setChannelPtzType(channel.getPtzType());
        }
        vo.setDeviceName(deviceNameOf(row.getDeviceId()));
        if (row.getStartTime() != null) {
            vo.setStartAt(TimeFormats.toDisplay(row.getStartTime()));
            vo.setDuration(Math.max(0,
                    java.time.Duration.between(row.getStartTime(),
                            java.time.LocalDateTime.now()).getSeconds()));
        }
        return vo;
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
     * 按 {@code serial} / {@code code} / {@code q} 过滤流列表并按游标切片。
     * 直播与回放的过滤原本各写一份，已在 {@code limit=0}（不分页）处理上走偏，故集中于此。
     *
     * @param limit 每页条数；{@code <= 0} 表示不分页
     */
    public static StreamListVo filterStreams(List<StreamVo> all, int start, int limit,
                                             String serial, String code, String q) {
        List<StreamVo> filtered = new ArrayList<>();
        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        for (StreamVo s : all) {
            if (serial != null && !serial.isBlank() && !serial.equals(s.getDeviceId())) {
                continue;
            }
            if (code != null && !code.isBlank() && !code.equals(s.getChannelId())) {
                continue;
            }
            if (needle != null) {
                String channelId = s.getChannelId() == null ? "" : s.getChannelId();
                String name = s.getChannelName() == null ? "" : s.getChannelName();
                if (!channelId.toLowerCase().contains(needle)
                        && !name.toLowerCase().contains(needle)) {
                    continue;
                }
            }
            filtered.add(s);
        }
        StreamListVo vo = new StreamListVo();
        vo.setStreamCount(filtered.size());
        // slice 内部已把 limit<=0 处理为「不分页」，此处无需再判一次
        vo.setStreams(PageQuery.slice(filtered, start, limit));
        return vo;
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
