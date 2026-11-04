package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通道快照的抓取编排：保证有一路可拉取的流 → 交给 {@link FfmpegSnapService} 解码成 JPEG → 存入 {@link SnapService}。
 *
 * <p>流已在推（云端录像保活）就复用，不发 INVITE；未在推则点播一次，抓完必停，不留副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapCaptureService {

    /** 抓拍时等待流上线的轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 300;

    /**
     * 抓拍时等待流上线的最长时长（毫秒）：实测级联下级有一次 INVITE 后 45 秒才推第一个 RTP 包，
     * 给 12 秒会把「能成的抓拍」判成失败。
     */
    private static final long ONLINE_TIMEOUT_MS = 25_000;

    /**
     * 流上线后的预热等待（毫秒），必须保留：ZLM 刚建媒体源时缓冲区的帧不一定从关键帧开始，
     * 立刻解码会报 {@code Could not find ref with POC xx}，解出半张花屏
     * （实测：上线 0 秒抓 → 下半屏花屏；预热 2 秒 → 正常）。
     */
    private static final long WARMUP_MS = 2_000;

    /** 抓拍失败时的重试次数。 */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 每次重试额外增加的预热时长（毫秒）：失败通常是流刚建立、还没攒出关键帧，
     * 递增可让首次几乎不等，总时长 2 + 3.5 + 5 ≈ 10.5 秒仍在接口可接受范围。
     */
    private static final long RETRY_EXTRA_MS = 1_500;

    /**
     * 同时抓拍的并发上限。必须限流：每个请求点播一路约 3Mbps，10 路就是 30Mbps，
     * 设备上行带宽撑不住会全部失败，还可能抖掉已正常的流。取 2 是一台设备通常能推的路数；
     * 拿不到许可时快速失败（前端显示稍后重试），排队会让用户以为界面卡死。
     */
    private static final int MAX_CONCURRENT = 2;

    /**
     * 会话已存在但非 ONLINE 时多久才允许重建（毫秒）：NEGOTIATING 说明设备正在推，
     * 立刻重建会打断它；实测有会话卡了十几分钟，故给 8 秒窗口。
     */
    private static final long REBUILD_AFTER_MS = 8_000;

    /** 全局并发许可。 */
    private final java.util.concurrent.Semaphore permits =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT);

    /** 正在抓拍的通道键（防重复：用户连点、自动补抓与手动抓同时触发）。 */
    private final java.util.Set<String> inFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final StreamService streamService;
    private final FfmpegSnapService ffmpegSnapService;
    private final SnapService snapService;
    private final DeviceService deviceService;
    /** SDK 直连设备走「设备自己抓图」，不拉流解码。 */
    private final DahuaCapabilityService dahuaCapability;

    /**
     * 抓取通道快照并存盘。
     *
     * @return 快照访问路径；失败抛 {@link BizException}
     */
    public String capture(String serial, String code) {
        GbChannel channel = deviceService.findChannel(serial, code);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + serial + "/" + code);
        }
        String key = serial + "/" + code;
        // 同一通道已有抓拍在跑：直接拒绝，避免并发拉同一路流
        if (!inFlight.add(key)) {
            throw BizException.badRequest("该通道正在抓拍，请稍候");
        }
        // 全局并发上限：设备上行带宽有限，同时拉太多路会全部失败
        boolean acquired = permits.tryAcquire();
        if (!acquired) {
            inFlight.remove(key);
            throw BizException.badRequest("抓拍任务繁忙（最多同时 " + MAX_CONCURRENT
                    + " 路），请稍后重试");
        }
        try {
            return doCapture(serial, code, channel, key);
        } finally {
            permits.release();
            inFlight.remove(key);
        }
    }

    private String doCapture(String serial, String code, GbChannel channel, String key) {
        /*
         * SDK 直连设备（大华等）让设备自己抓图，不要拉流解码。
         *
         * 这是两种代价完全不同的做法：
         *   拉流解码 —— 要拉起一路 2 Mbps 的流、等关键帧、再用 ffmpeg 解一帧；
         *              一次抓图跑几百 KB 到几 MB 的设备上行带宽，还要占一个 ffmpeg 进程。
         *   设备直抓 —— 一条 SDK 指令，设备返回一张几十 KB 的 JPEG，
         *              平台不解码、不拉流、不需要 ffmpeg。
         *
         * 所以只要设备支持 SDK 接入，就必须走这条，绝不能回落到拉流。
         */
        if (dahuaCapability.isDahua(serial)) {
            return captureBySdk(serial, code);
        }

        // 决定是否需要（重新）点播。不能只看「压根没有会话」：最常见的是有会话但卡在
        // NEGOTIATING（设备没推流、上次 INVITE 丢了），不重建就会白等 25 秒超时。
        RtpSession session = streamService.sessionByKey(key);
        boolean needInvite = session == null;
        if (session != null && !RtpSession.ONLINE.equals(session.getState())
                && System.currentTimeMillis() - session.getCreatedAt() > REBUILD_AFTER_MS) {
            log.info("抓拍：会话停留在 {} 超过 {} ms，重建一次: {}",
                    session.getState(), REBUILD_AFTER_MS, key);
            needInvite = true;
        }
        boolean started = false;
        if (needInvite) {
            // startLive 内部会区分可复用的活会话与死会话，后者先关再 INVITE，不留僵尸会话
            streamService.startLive(serial, code);
            started = true;
        }

        try {
            RtpSession live = awaitOnline(key);
            if (live == null) {
                throw BizException.badRequest("通道流未就绪，无法抓拍：" + code);
            }
            String flvUrl = ffmpegSnapService.localFlvUrl(live);
            byte[] jpeg = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS && jpeg == null; attempt++) {
                // 预热：等 ZLM 攒出完整关键帧，否则会解出花屏
                sleep(WARMUP_MS + (attempt - 1) * RETRY_EXTRA_MS);
                // 【降级】前两次要求关键帧（画面完整），最后一次放开 —— 推流初期可能几秒内没有关键帧，
                // 可能花屏的图也比抓拍失败有用。
                boolean requireKey = attempt < MAX_ATTEMPTS;
                jpeg = ffmpegSnapService.capture(flvUrl, requireKey);
                if (jpeg == null) {
                    log.debug("抓拍未成功，重试 {}/{}: serial={} code={} requireKeyFrame={}",
                            attempt, MAX_ATTEMPTS, serial, code, requireKey);
                }
            }
            if (jpeg == null) {
                throw BizException.badRequest("抓拍失败：无法解码该通道的编码流");
            }
            String url = snapService.saveBytes(serial, code, jpeg);
            if (url == null) {
                throw BizException.badRequest("快照保存失败");
            }
            log.info("抓拍成功: serial={} code={} size={}", serial, code, jpeg.length);
            return url;
        } finally {
            // 只有自己点播的才停；复用的保活流不能动
            if (started) {
                try {
                    streamService.stop(serial, code);
                } catch (Exception e) {
                    log.warn("抓拍后停流失败: serial={} code={} {}", serial, code, e.getMessage());
                }
            }
        }
    }

    /**
     * 让设备自己抓图（大华 SDK 直连）。
     *
     * <p>不拉流、不解码，一张图只有几十 KB。这是「低流量抓图」诉求的唯一落点。
     */
    private String captureBySdk(String serial, String code) {
        var session = dahuaCapability.requireSession(serial);
        int sdkChannel = dahuaCapability.sdkChannel(session, serial, code);
        byte[] jpeg;
        try {
            jpeg = dahuaCapability.ext().snapshot(session, sdkChannel);
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw dahuaCapability.translate("抓图", e);
        }
        if (jpeg == null || jpeg.length == 0) {
            throw BizException.badRequest("设备返回空图像：" + code);
        }
        String url = snapService.saveBytes(serial, code, jpeg);
        if (url == null) {
            throw BizException.badRequest("快照保存失败");
        }
        log.info("抓拍成功（设备直抓）: serial={} code={} sdkChannel={} size={}",
                serial, code, sdkChannel, jpeg.length);
        return url;
    }

    /**
     * 等待通道的流上线（INVITE → 200 OK → 推 RTP → ZLM 解出轨道，实测通常 1~3 秒）。
     *
     * @return 已上线的会话；超时返回 {@code null}
     */
    private RtpSession awaitOnline(String key) {
        long deadline = System.currentTimeMillis() + ONLINE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            RtpSession session = streamService.sessionByKey(key);
            if (session != null && RtpSession.ONLINE.equals(session.getState())) {
                return session;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 睡眠（忽略中断）。 */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
