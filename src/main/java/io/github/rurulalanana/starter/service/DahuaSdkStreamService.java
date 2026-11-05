package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.gb28181.sip.zlm.EsMediaPublisher;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华 SDK 取流的编排：SDK 拿裸 H264/H265 → 直接喂 ZLM 建流。
 *
 * <h3>为什么需要这条路径</h3>
 * 平台原有的大华预览走 RTSP：由 ZLM <b>反向</b>连设备的
 * {@code rtsp://<ip>:554/cam/realmonitor}。局域网可用，
 * 但设备在 NAT / 4G 之后时 554 从平台侧<b>根本不可达</b> ——
 * 实测同一台设备 554 / 37777 / 80 三个端口从公网全部连不上，
 * 只有设备主动连出来的注册连接是通的。此时 RTSP 播放会一直重试、永远出不了图。
 *
 * <p>SDK 取流走的是<b>已建立的登录会话</b>，方向与主动注册一致，天然穿透 NAT。
 *
 * <h3>为什么是裸流而不是国标 PS</h3>
 * 早期试过 {@code EM_REAL_DATA_TYPE_GBPS} 拿 PS 再打 RTP 灌国标收流端口，
 * 看着更统一，但实测<b>主动注册会话上 SDK 只给大华私有 DHAV、拿不到 PS</b>
 * （直连会话可以）—— 而 NAT 场景恰恰只有注册会话可用。
 * 改成要裸 H264 后两种会话都正常，这也是大华官方 demo 的默认用法。
 * 裸流由 ZLM 的 {@code mk_media_input_h264/265} 直接建流，
 * 下游 FLV/HLS/WebRTC/RTSP 输出与国标设备完全一致，前端一行都不用改。
 *
 * <h3>什么时候用这条路径</h3>
 * 只在设备<b>当前有 SDK 会话</b>时启用（主动注册或已登录直连）。
 * 没有会话的部署（纯局域网、SDK 不可用）仍旧走 RTSP 代理，行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DahuaSdkStreamService {

    /** 首帧等待上限：局域网首帧通常 200ms 内到，NAT 后稍慢，3 秒足够。 */
    private static final long FIRST_FRAME_WAIT_MS = 3_000L;

    /** 空闲回收宽限：播放器重连的间隙不能被误判成「没人看」。 */
    private static final long IDLE_GRACE_MS = 60_000L;

    private final DahuaCapabilityService capability;
    private final DeviceService deviceService;
    private final Gb28181MediaPlatform platform;

    /** 一路 SDK 取流：SDK 句柄 + ZLM 发布器。 */
    public record LiveStream(String key, String app, String stream,
                             DahuaExtAccess.RealPlay realPlay, EsMediaPublisher publisher) {
    }

    /** 正在取的流：{@code deviceId/channel} → 句柄。 */
    private final Map<String, LiveStream> running = new ConcurrentHashMap<>();

    /** 各流「最近一次确认有观众」的时刻（毫秒），用于空闲回收。 */
    private final Map<String, Long> lastViewerAt = new ConcurrentHashMap<>();

    /**
     * 设备此刻是否能用 SDK 取流。
     *
     * <p>判据是「有活跃 SDK 会话」而不是「设备是主动注册模式」：
     * 局域网直连的设备同样有会话，走 SDK 一样能出图。
     */
    public boolean available(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        DahuaSdk.Session session = DahuaSdk.get().sessionBySerial(deviceId);
        return session != null && session.valid();
    }

    /**
     * 启动一路 SDK 取流。
     *
     * @param deviceId 设备编号（大华即序列号）
     * @param channel  通道编码（{@code <deviceId>-<n>}）
     * @return 取流句柄；失败抛异常
     */
    public LiveStream start(String deviceId, String channel) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DahuaSdk.Session session = DahuaSdk.get().sessionBySerial(deviceId);
        if (session == null || !session.valid()) {
            throw BizException.badRequest("大华设备未登录，无法 SDK 取流：" + deviceId);
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }

        String key = deviceId + "/" + channel;
        stop(key);

        int sdkChannel = sdkChannelOf(session, deviceId, channel);

        /*
         * app = 设备编码、stream = 通道编码，与国标收流的口径一致：
         * 播放地址、ZLM 媒体源路径、前端播放器都不用区分流的来源。
         */
        EsMediaPublisher publisher;
        try {
            publisher = media.createEsPublisher(deviceId, channel);
        } catch (RuntimeException e) {
            throw BizException.badRequest("创建媒体源失败：" + e.getMessage());
        }

        DahuaExtAccess.RealPlay play;
        try {
            // dataType 只作诊断用（H265 也可能报 1004），编码由发布器从 NAL 头判定
            play = capability.ext().startRealPlay(session, sdkChannel, key,
                    (es, dataType) -> publisher.push(es));
        } catch (RuntimeException e) {
            publisher.close();
            throw BizException.badRequest("大华 SDK 取流失败：" + e.getMessage());
        }

        /*
         * 等首帧：ZLM 在收到媒体数据前不会真正注册流，
         * 提前返回的播放地址前端打开是 404（表现为「有地址但播不了」）。
         */
        long deadline = System.currentTimeMillis() + FIRST_FRAME_WAIT_MS;
        while (System.currentTimeMillis() < deadline && !publisher.hasFirstFrame()) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!publisher.hasFirstFrame()) {
            // 不留死流：SDK 句柄与媒体源一起收掉，接口明确报错
            try {
                play.close();
            } catch (Throwable ignored) {
                // 已失败，清理异常不再向上抛
            }
            publisher.close();
            throw BizException.badRequest(
                    "大华 SDK 取流超时：已登录设备但 " + FIRST_FRAME_WAIT_MS
                            + "ms 内没有拿到视频帧。请检查该通道是否真的有画面"
                            + "（通道号：IPC 自身是 0，NVR 下挂是 1~N）");
        }

        LiveStream live = new LiveStream(key, deviceId, channel, play, publisher);
        running.put(key, live);
        lastViewerAt.remove(key);
        log.info("大华 SDK 取流已启动: {} 通道={} codec={} 累计帧={}",
                key, sdkChannel, publisher.codecName() == 0 ? "H264" : "H265",
                publisher.frames());
        return live;
    }

    /**
     * SDK 通道号。
     *
     * <p>与云台那条链路同一套口径：<b>IPC 自身是通道 0</b>，NVR 下挂摄像机是 1~N。
     * 用 1 取 IPC 的码流会报 {@code 0x80000007}（参数不合法）——
     * 实测踩过，改成 0 立刻成功。
     */
    private int sdkChannelOf(DahuaSdk.Session session, String deviceId, String channel) {
        int index = io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channel);
        return session.ipc() ? Math.max(0, index - 1) : index;
    }

    /** 停止一路 SDK 取流（幂等）。 */
    public void stop(String key) {
        LiveStream live = running.remove(key);
        lastViewerAt.remove(key);
        if (live == null) {
            return;
        }
        try {
            live.realPlay().close();
        } catch (Throwable t) {
            log.debug("停止 SDK 取流失败: {} {}", key, t.toString());
        }
        try {
            live.publisher().close();
        } catch (Throwable t) {
            log.debug("关闭发布器失败: {} {}", key, t.toString());
        }
        /*
         * 清通道的 stream_id —— 它是通道列表判断「有流 / 显示停止按钮」的唯一依据。
         *
         * 不清的后果（实测）：用户关掉页面 → 60 秒后这里静默回收了 SDK 流，
         * 但通道列表仍然显示「停止」按钮、numOutputs=1，看起来像一直在推流。
         * gb_stream 那条记录由 ZLM 的 onStreamOffline 事件置 CLOSED（见 StreamEventBridge），
         * 这里只负责通道侧。
         */
        try {
            deviceService.setChannelStream(live.app(), live.stream(), null);
        } catch (Exception e) {
            log.debug("清除通道 stream_id 失败: {} {}", key, e.toString());
        }
        log.info("大华 SDK 取流已释放: {}", key);
    }

    /** 某设备通道当前是否已在取流。 */
    public boolean isRunning(String deviceId, String channel) {
        return running.containsKey(deviceId + "/" + channel);
    }

    /** 当前正在取的流数量（诊断用）。 */
    public int runningCount() {
        return running.size();
    }

    /**
     * 空闲回收：没人看就停掉 SDK 取流。
     *
     * <p>国标流由 {@code StreamService.reapIdleStreams} 按 RTP 会话回收，
     * 而 SDK 流<b>没有 RtpSession</b>，那个巡检扫不到 —— 不单独回收的话，
     * 用户关掉页面后设备会一直被取流，白占设备上行与编码资源。
     *
     * <p>判据用 ZLM 的 {@code total_reader_count}（含 MP4 录制器：
     * {@code protocol.mp4_as_player=1}），所以「只录不看」不会被误关。
     *
     * <p><b>必须尊重通道的「常开」设置</b>：{@code gb_channel.on_demand=false}
     * 表示用户要求这路流一直在线（对应国标语义的「非常开按需」）。
     * 早先只看读者数，开关设了常开也照样 60 秒回收，
     * 表现为「设了常开还是过一会儿就断」—— 与国标那条路径的口径保持一致。
     */
    @Scheduled(fixedDelay = 20_000L, initialDelay = 30_000L)
    public void reapIdleStreams() {
        if (running.isEmpty()) {
            return;
        }
        ZlmMediaServer media = platform.mediaServer();
        if (media == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, LiveStream> e : running.entrySet()) {
            String key = e.getKey();
            LiveStream live = e.getValue();
            try {
                /*
                 * 常开通道不回收：录像录制中的流由 ZLM 的录制定时器维持读者数，
                 * 但「只开常开、不录像」的通道没有读者也要一直在线。
                 * 查库有成本，所以先用内存里的 readers 判，只有 0 读者才去查开关。
                 */
                int readers = media.readerCount(live.app(), live.stream());
                if (readers > 0) {
                    lastViewerAt.put(key, now);
                    continue;
                }
                if (isKeepAliveChannel(live.app(), live.stream())) {
                    // 常开：不回收，但要持续刷新时间戳，避免关掉开关后立刻被判超时
                    lastViewerAt.put(key, now);
                    continue;
                }
                Long seen = lastViewerAt.get(key);
                if (seen == null) {
                    // 首次采样到 0：先记时间，给播放器重连的间隙留出宽限
                    lastViewerAt.put(key, now);
                    continue;
                }
                // 设备断线（SDK 回调停了）也要回收：地址还在但永远没新帧
                boolean stalled = now - live.publisher().lastFrameAt() > IDLE_GRACE_MS;
                if (now - seen >= IDLE_GRACE_MS || stalled) {
                    log.info("回收空闲的大华 SDK 流: {}（{} 秒无观众{}）", key,
                            (now - seen) / 1000, stalled ? "，且已无新帧" : "");
                    stop(key);
                }
            } catch (Exception ex) {
                log.debug("回收 SDK 流失败: {} {}", key, ex.toString());
            }
        }
    }

    /**
     * 该通道是否要求流常开（{@code on_demand = false}）。
     *
     * <p>查库失败时返回 {@code false}（按按需处理）：宁可多回收一次，
     * 也不要因为一次数据库抖动把「按需」通道永久占着设备上行。
     */
    private boolean isKeepAliveChannel(String deviceId, String channelId) {
        try {
            var ch = deviceService.findChannel(deviceId, channelId);
            return ch != null && Boolean.FALSE.equals(ch.getOnDemand());
        } catch (Exception e) {
            log.debug("读取通道按需设置失败（按按需处理）: {}/{} {}", deviceId, channelId, e.toString());
            return false;
        }
    }
}
