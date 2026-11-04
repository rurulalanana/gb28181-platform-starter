package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.gb28181.sip.zlm.PsRtpSender;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华 SDK 取流的编排：SDK 拿 PS → 打 RTP → 灌进 ZLM 的国标收流端口。
 *
 * <h3>为什么必须有这条路径</h3>
 * 平台原有的大华预览走 RTSP：由 ZLM <b>反向</b>连设备的
 * {@code rtsp://<ip>:554/cam/realmonitor}。这在局域网可用，
 * 但设备一旦位于 NAT / 4G 之后，554 从平台侧<b>根本不可达</b> ——
 * 实测同一台设备 554 / 37777 / 80 三个端口从公网全部连不上，
 * 只有设备主动连出来的注册连接是通的。此时 RTSP 播放会一直重试、永远出不了图。
 *
 * <p>SDK 取流走的是<b>已建立的登录会话</b>，方向与主动注册一致，天然穿透 NAT。
 *
 * <h3>为什么是「打 RTP 灌 ZLM」而不是自己解 PS</h3>
 * SDK 侧 {@code CLIENT_RealPlayByDataType + EM_REAL_DATA_TYPE_GBPS} 直接给标准 PS
 * （实测首包 {@code 00 00 01 BA}），而 ZLM 的国标收流端口本来就只收 PS over RTP。
 * 于是把 PS 打成 RTP 发过去，ZLM 侧的解复用、轨道建立、SSRC 映射、
 * 会话生命周期、FLV/HLS/WebRTC/RTSP 输出<b>全部原样复用</b>，
 * 与国标设备走完全相同的链路 —— 前端一行都不用改。
 *
 * <h3>什么时候用这条路径</h3>
 * 只在设备<b>当前有 SDK 会话</b>（主动注册或已登录直连）时启用。
 * 没有会话的部署（纯局域网）仍旧走 RTSP，行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DahuaSdkStreamService {

    private final DahuaCapabilityService capability;
    private final DeviceService deviceService;

    /** 一路 SDK 取流。ZLM 侧 RTP 会话的生命周期由 StreamService 管，这里只管 SDK 侧。 */
    public record LiveStream(String key, DahuaExtAccess.RealPlay realPlay, PsRtpSender sender) {
    }

    /** 正在取的流：{@code deviceId/channel} → 句柄。 */
    private final Map<String, LiveStream> running = new ConcurrentHashMap<>();

    /**
     * 设备此刻是否能用 SDK 取流。
     *
     * <p>判据是「有活跃 SDK 会话」而不是「设备是主动注册模式」：
     * 局域网直连的设备同样有会话，走 SDK 一样能出图，没必要非走 RTSP。
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
     * <p>调用方（{@link StreamService}）负责先 {@code sessionManager.open(...)}
     * 拿到收流端口与 SSRC，本方法只负责「SDK 拿 PS → 打进那个端口」。
     *
     * @param deviceId 设备编号（大华即序列号）
     * @param channel  通道编码（{@code <deviceId>-<n>}）
     * @param rtp      ZLM 收流会话（已 open，端口就绪）
     * @return 取流句柄；失败抛异常
     */
    public LiveStream start(String deviceId, String channel, RtpSession rtp) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DahuaSdk.Session session = DahuaSdk.get().sessionBySerial(deviceId);
        if (session == null || !session.valid()) {
            throw BizException.badRequest("大华设备未登录，无法 SDK 取流：" + deviceId);
        }

        String key = deviceId + "/" + channel;
        stop(key);

        int sdkChannel = sdkChannelOf(session, deviceId, channel);
        /*
         * 收流地址固定用回环 127.0.0.1：RTP 是我们自己发给本进程内嵌的 ZLM，
         * 走外网地址会绕一圈还可能被防火墙拦。ZLM 按 SSRC 分流，不看来源 IP。
         */
        PsRtpSender sender;
        try {
            sender = new PsRtpSender(key, "127.0.0.1", rtp.getPort(), rtp.getSsrc());
        } catch (Exception e) {
            throw BizException.badRequest("RTP 发送器创建失败：" + e.getMessage());
        }

        DahuaExtAccess ext = capability.ext();
        DahuaExtAccess.RealPlay play;
        try {
            play = ext.startRealPlay(session, sdkChannel, key, sender::send);
        } catch (RuntimeException e) {
            sender.close();
            throw BizException.badRequest("大华 SDK 取流失败：" + e.getMessage());
        }

        /*
         * 快速失败：等第一块 PS。
         *
         * <p>为什么要等：SDK 的 {@code EM_REAL_DATA_TYPE_GBPS} 只在<b>直连会话</b>上
         * 做 DHAV→PS 转换；在<b>主动注册会话</b>上实测只投递 DHAV（{@code dwDataType=0}，
         * 首字节 {@code 44 48 41 56}），拿不到 PS。此时若直接返回成功，
         * 平台会留下一个永远 {@code NEGOTIATING} 的会话 —— 前端一直转圈、
         * 也没有任何可读的错误，比直接报错更难排查。
         *
         * <p>2 秒足够：实测直连会话第一块 PS 在 200ms 内到达。
         */
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline && sender.blocks() == 0) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (sender.blocks() == 0) {
            try {
                play.close();
            } catch (Throwable ignored) {
                // 已失败，清理异常不再向上抛
            }
            sender.close();
            throw BizException.badRequest("大华 SDK 没有输出国标 PS 码流："
                    + "该设备的主动注册会话上 SDK 只给私有 DHAV 封装（不支持转为 PS）。"
                    + "请改用「设备直连（局域网/端口映射）」或「设备走国标接入」——"
                    + "详见 docs/排障实录.md §11");
        }

        LiveStream live = new LiveStream(key, play, sender);
        running.put(key, live);
        log.info("大华 SDK 取流已启动: {} 通道={} rtpPort={} ssrc={}",
                key, sdkChannel, rtp.getPort(), rtp.getSsrc());
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
        if (live == null) {
            return;
        }
        try {
            live.realPlay().close();
        } catch (Throwable t) {
            log.debug("停止 SDK 取流失败: {} {}", key, t.toString());
        }
        try {
            live.sender().close();
        } catch (Throwable t) {
            log.debug("关闭 RTP 发送器失败: {} {}", key, t.toString());
        }
        log.info("大华 SDK 取流已释放: {}", key);
    }

    /** 当前正在取的流数量（诊断用）。 */
    public int runningCount() {
        return running.size();
    }

    /** 该设备是否已有一路在取。 */
    public boolean isRunning(String key) {
        return running.containsKey(key);
    }
}
