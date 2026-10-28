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

    /**
     * 「此刻是否应该录」的权威判定（用户开关 ∪ 录像计划）。
     *
     * <p>空闲流回收必须用它而不是直接读 {@code gb_channel.cloud_record}：
     * 录像计划不再写库，只读库会看不到计划生效的通道，导致计划刚开录、
     * 流就被当成「无读者」回收掉。
     */
    private final ChannelRecordPolicy recordPolicy;

    // ==================== 点播 ====================

    /**
     * 开始实时直播（标准 9.2）。
     *
     * @param deviceId  设备编码（SIP 目标，全局唯一）
     * @param channelId 通道编码（= ZLM 流名）；{@code null} 或与设备编码相同表示设备自身
     * @return 流信息
     */
    public GbStream startLive(String deviceId, String channelId) {
        return startLive(deviceId, channelId, null);
    }

    /**
     * 平台主动点播。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码
     * @param streamNumber 码流编号（GB/T 28181—2022 附录 G：SSRC 后 4 位）；
     *                     {@code null} 表示按设备配置的「码流索引」取值
     */
    public GbStream startLive(String deviceId, String channelId, Integer streamNumber) {
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
        // 没有活会话，但设备侧可能仍在推上一轮的流（下级平台收不到 BYE 会一直推）。
        // 此时若直接发 INVITE，设备会为同一通道开第二路推流 —— 实测 LiveGBS 真的会
        // 推第二路（每路约 3 Mbps），同一通道多点几次就攒成多路，用户看到的就是
        // 「级联那边显示还有 N 个视频正在推送」。先把已在推的那路认领回来即可。
        RtpSession adopted = platform.sessionManager().reclaimOrphanForChannel(deviceId, channel);
        if (adopted != null) {
            GbStream stream = toStream(adopted, GbStream.TYPE_LIVE);
            save(stream);
            deviceService.setChannelStream(deviceId, channel, stream.getStreamKey());
            return stream;
        }
        // 平台重启后 SIP 注册表为空（纯内存），而设备要到下次心跳才自愈，
        // 空窗期内点播会报「设备未注册或会话已失效」。这里按库里的地址先补一次，
        // 让重启后立刻点播也能成功。
        healRegistryIfNeeded(ch);
        RtpSession session = platform.mediaService().play(deviceId, channel, resolveStreamNumber(deviceId, streamNumber));
        GbStream stream = toStream(session, GbStream.TYPE_LIVE);
        save(stream);
        deviceService.setChannelStream(deviceId, channel, stream.getStreamKey());
        return stream;
    }

    /**
     * 决定本次点播用的码流编号。
     *
     * <p>优先级：请求显式指定的 {@code streamnumber} &gt; 设备配置的「码流索引」&gt; 自动。
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
     * 取该通道当前的实时会话（没有则返回 {@code null}）。
     *
     * <p>供云端录像保活判断「该不该再点播一次」用：已有会话时不必重复建流。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码
     * @return 会话；不存在或已 CLOSED 时返回 {@code null}
     */
    public RtpSession liveSession(String deviceId, String channelId) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        RtpSession s = platform.sessionManager().getByChannel(deviceId, channel);
        return s == null || RtpSession.CLOSED.equals(s.getState()) ? null : s;
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
        return startPlayback(deviceId, channelId, item, 1);
    }

    /**
     * 开始历史回放，可指定下载倍速。
     *
     * <p><b>倍速为什么走回放</b>：标准 9.9 的 {@code s=Download} 设备支持度很差，
     * 本平台的「下载」复用回放链路 + 本地录制；而 9.9.1 b) 规定倍速由请求方
     * 在 SDP 里携带（{@code a=downloadspeed:N}），与 {@code s=} 取值无关。
     *
     * <p><b>倍速大于 1 时不复用已有会话</b>：会话的推流速率由<b>上一次 INVITE
     * 的 SDP</b> 决定，复用会让 8 倍速静默退化成 1 倍速 ——
     * 用户等满一小时后才发觉得到的文件更慢，且无从判断是设备不支持还是代码没生效。
     * 因此明确停掉旧会话后重建，并在日志里留下倍速值。
     *
     * @param downloadSpeed 倍速档位（设备 {@code Info/DownloadSpeed} 声明的值）；
     *                      {@code 1} 表示按实时速度推流
     */
    public GbStream startPlayback(String deviceId, String channelId, RecordItem item,
                                  int downloadSpeed) {
        String channel = (channelId == null || channelId.isBlank()) ? deviceId : channelId;
        RtpSession existing = platform.sessionManager()
                .getByChannel(deviceId, channel, MediaType.PLAYBACK);
        if (existing != null && !RtpSession.CLOSED.equals(existing.getState())) {
            /*
             * 判断「是不是同一段录像」必须比较**会话身份**而不是 filePath：
             * 大量设备（实测 LiveGBS、多数国产摄像机）不提供 FilePath，
             * 只按时间段管理录像。改造前 want=null 时无条件复用，
             * 于是点不同时间段都命中同一个卡在 NEGOTIATING 的会话 ——
             * 界面上表现为「换时间点播毫无反应，也不报错」。
             */
            String want = recordIdentity(item);
            String have = existing.getMediaUri();
            boolean same = want != null && want.equals(have);
            /*
             * 只有**正在推流**的会话才值得复用。
             *
             * <p>会话对象在设备停推后仍会留在表里（状态变 OFFLINE），而它对应的
             * ZLM 媒体源已经被摘除。此时若按「同一段就复用」返回这个僵尸会话，
             * 播放器拿到的流永远没有数据 —— 用户看到的是「继续播放后画面不动」，
             * 而日志里一切正常。
             *
             * <p>典型触发路径：暂停（发 SIP PAUSE 让设备停推）超过 ZLM 的
             * RTP 超时（{@code RtpProcess timeout}，约 15~20 秒）后媒体源被注销，
             * 用户再点「继续」。
             */
            if (same && downloadSpeed <= 1 && RtpSession.ONLINE.equals(existing.getState())) {
                return toStream(existing, GbStream.TYPE_PLAYBACK);
            }
            /*
             * 走到这里只剩两种意图：换一段录像，或同一段要求更高倍速。
             * 两者都必须**销毁旧会话**（推流速率由上一次 INVITE 的 SDP 决定，
             * 不复用就只能重建），所以把下载锁的检查放在这两条路的<b>共同入口</b>。
             *
             * <p><b>不能漏掉「提速」那条路</b>：它同样会 stop 掉下载中的会话。
             * 早先只在「换段」分支里检查，前端靠按钮禁用挡住了，
             * 但接口层不该指望调用方守规矩 —— 一个直接构造的请求就能打断下载。
             *
             * <p><b>键必须取自会话本身</b>（{@code existing.getSessionKey()}）
             * 而不是在这里拼一份副本：真实规则在
             * {@code RtpSessionManager#sessionKey}，设备编码或通道编码缺失时
             * app 与 stream 会互相回退。自己拼的第二份实现迟早会与它算得不一样，
             * 而算错的结果是一个永远锁不住的锁（比没有锁更糟：看着有防护）。
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
                 * 请求的是**另一段**录像 → 显式停掉旧会话。
                 *
                 * <p>不显式停会让 {@code sessionManager.open} 用新会话顶掉旧的，
                 * 旧会话对应的 ZLM 媒体源与录制随之重置，正在跑的下载只
                 * 得到一段只有开头几十秒的文件。用户看到的现象是
                 * 「下载时长严重不足」，而日志里一点异常都没有。
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
     *
     * <p>下载与回放共用一路回放流，而回放会话同通道只有一个。若允许
     * 用户此时点开另一段回放，那个会话会被顶掉、下载随之断流，
     * 产物只有开头一小段（这是「下载时长不够」最主要的原因）。
     * 因此下载期间锁定该通道的回放会话，从源头拒绝而不是事后补救。
     */
    private final java.util.Set<String> downloadLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
     *
     * <p>与 {@code Gb28181MediaService#playback} 写入 {@code mediaUri} 的规则
     * <b>必须完全一致</b>，否则复用判断永远不成立、每点一次都重开会话。
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

    /**
     * 按<b>任意一种</b>外部标识定位会话。
     *
     * <p>接口对外的 {@code streamId} 有三种历史形态，调用方会原样传回来：
     * <ol>
     *   <li>会话键 {@code <设备编码>/<通道编码>} —— 列表/启动接口返回的就是它；</li>
     *   <li>裸通道编码 —— 直连摄像机场景，设备编码即通道编码；</li>
     *   <li>ZLM 流名 —— 早期版本返回过，外部脚本仍在用。</li>
     * </ol>
     * 三种都要认，且<b>顺序不能变</b>：会话键最精确，其次是通道编码，最后才遍历流名。
     *
     * <p><b>为什么必须集中在一处</b>：这个方法原本在 {@code StreamController}、
     * {@code PlaybackController}、{@code DecodeController} 里各有一份拷贝
     * （分别叫 {@code findSessionByStreamId} / {@code findSession}），
     * 三份的实现悄悄走偏了 —— {@code /stream/info} 那两份只认后两种形态，
     * 于是拿列表接口自己返回的 {@code streamId} 回查恒 404
     * （{@code 直播流不存在：3402…/3402…}），而回放侧因为多写了一步
     * {@code sessionByKey} 所以正常。同一件事写三遍，就一定会有一遍是错的。
     *
     * @param streamId 会话键 / 通道编码 / 流名
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
     *
     * <p>「会话存在」不等于「媒体源就绪」：会话在 INVITE 发出后立刻创建，
     * 而 ZLM 要等设备推来第一个 RTP 包才注册媒体源。
     * 录制、抓拍等依赖媒体源的操作必须用本方法判断就绪。
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
     *
     * <p><b>为什么必须补</b>：进程被强杀（{@code gb.sh} 优雅停止超时后会强制 kill）
     * 时来不及发 BYE，设备/下级平台不知道我方已退出，会一直推下去 ——
     * 实测 LiveGBS 每路约 3 Mbps 且从不超时，多重启几次就攒成多路重复推流，
     * 用户看到的就是「级联那边显示还有 N 个视频正在推送」，只能人工清理。
     *
     * <p>平台侧已无对话信息（内存会话随进程消失），只能靠库里持久化的 Call-ID 补发。
     * 实测 LiveGBS 按 Call-ID 匹配，不带原 tag 也会回 200 OK 并停止推流；
     * 对严格实现的设备，已结束的对话会回 481 —— 无害。
     *
     * @param stale 上一轮遗留（未 CLOSED）的流记录
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
     *
     * <p><b>为什么必须补</b>：平台刚启动时 SIP 注册表是空的（纯内存），设备要到
     * 下一次心跳才回来 —— 实测启动瞬间补发 BYE 直接报「设备未注册或会话已失效」，
     * 于是回收动作全部失效，残余推流继续跑。用库里存的 {@code remote_ip/remote_port}
     * 先把会话建起来即可（与点播路径的 {@code healRegistryIfNeeded} 同一套机制）。
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
     * 故只处理「已 ONLINE 超过宽限期」的会话。
     *
     * <p><b>宽限期必须用「运行时配置的当前值」</b>：该时长可在界面上热更新
     * （存库 + 推给 ZLM）。若这里读 {@code mediaServer.noneReaderDelayMs()}，
     * 拿到的是<b>启动时的 yml 快照</b> —— 用户把时长调到 120 秒后，
     * ZLM 确实晚关了，但平台巡检仍按旧的 45 秒提前关，
     * 界面看起来「改了没生效」。故由调用方传入（{@link PlatformScheduler}
     * 从 {@link RuntimeConfigService} 实时读取）。
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
                // 【必须尊重通道的「常开」设置，否则按需/常开开关形同虚设】
                //
                // 原实现只按 ZLM 读者数判断，等于「所有通道都按需」——
                // 用户把 onDemand 关掉（期望常开）也照样被关流，
                // 表现为「设了常开还是过一会儿就断」。
                if (keepAlive.contains(session.getChannelId())) {
                    continue;
                }
                if (mediaServer.readerCount(session.getZlmApp(), session.getStream()) > 0) {
                    continue;
                }
                log.info("空闲流巡检：{} 秒内无观看者，关闭会话 {}", grace / 1000, session.getSessionKey());
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
     * 找出「要求流常开、不能被空闲回收」的通道编码集合。
     *
     * <p>两类通道必须常开：
     * <ol>
     *   <li><b>此刻应该录云端录像</b> —— 用户手动开关 <b>或</b> 录像计划生效，
     *       判定统一走 {@link ChannelRecordPolicy#enabledChannels()}。
     *       虽然 ZLM 侧 {@code protocol.mp4_as_player=1} 让录制器算作「读者」，
     *       但录制尚未开始的那一瞬间读者数仍是 0，存在被误关的竞态；</li>
     *   <li><b>用户显式设置常开</b>（{@code on_demand=false}）—— 用户的明确意图。</li>
     * </ol>
     *
     * <p><b>为什么录像判定要走 recordPolicy 而不是直接读 {@code cloud_record}</b>：
     * 录像计划不再写数据库（见 {@link ChannelRecordPolicy} 的说明），
     * 只读库会看不到计划生效的通道 —— 于是计划刚开录、流就因「无读者」被回收，
     * 表现为「计划里配了却录不起来」。
     *
     * <p>返回 {@code Set} 而不是逐个查：本方法被空闲流巡检每 20 秒调用一次，
     * 一次全量查库（几千行以内）远优于循环里 N 次单条查询。
     *
     * <p>查库失败返回空集合：此时退化为「全部按需」，与旧行为一致，
     * 不会因为一次查询异常就把流全留着不关。
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

    /**
     * 按 {@code serial} / {@code code} / {@code q} 过滤流列表并按游标切片。
     *
     * <p><b>为什么抽出来</b>：直播 {@code /stream/list} 与回放
     * {@code /playback/streamlist} 的这段过滤原本各写了一份，除了列表来源
     * （{@code liveList} / {@code playbackList}）外逐行相同。两份拷贝不会永远
     * 保持同步 —— 本次筛查时就发现它们在 {@code limit=0}（不分页）的处理上已经
     * 不一致了：一边特判、另一边直接切片。
     *
     * @param all    源列表
     * @param start  偏移量
     * @param limit  每页条数；{@code <= 0} 表示不分页
     * @param serial 设备编码过滤，可为空
     * @param code   通道编码过滤，可为空
     * @param q      关键字（通道编码 / 通道名），可为空
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
