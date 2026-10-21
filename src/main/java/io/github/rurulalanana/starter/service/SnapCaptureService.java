package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 通道快照的抓取编排。
 *
 * <p>职责：<b>保证有一路可拉取的流</b> → 交给 {@link FfmpegSnapService} 解码成 JPEG
 * → 存到 {@link SnapService}。
 *
 * <h2>为什么要「先确保有流」</h2>
 *
 * <p>快照的图像来自正在推的那路流。平台的流分两种存在方式：
 * <ul>
 *   <li><b>已在推</b>（云端录像保活任务 {@code keepCloudRecordStreams} 会持续保持
 *       开了云端录像的通道在线）—— 直接复用，不发 INVITE、不占设备额外带宽；</li>
 *   <li><b>未在推</b> —— 需要点播一次。此时抓完要停掉，
 *       否则会一直占着设备的一路推流。</li>
 * </ul>
 * 本类用「复用优先、必要时点播、结束必停」的策略，两种情况都不留下副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapCaptureService {

    /** 抓拍时等待流上线的轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 300;

    /**
     * 抓拍时等待流上线的最长时长（毫秒）。
     *
     * <p><b>为什么给到 25 秒</b>：级联下级设备收到 INVITE 后不一定立刻推流。
     * 实测有一次 INVITE 发出后 <b>45 秒</b>才收到第一个 RTP 包（流上线），
     * 期间会话一直是 NEGOTIATING。12 秒太短，会把「能成的抓拍」判成失败。
     */
    private static final long ONLINE_TIMEOUT_MS = 25_000;

    /**
     * 流上线后的预热等待（毫秒）。
     *
     * <p><b>这不是拍脑袋的延时，是必须的</b>：ZLM 刚建立媒体源时，
     * 缓冲区里的帧不一定从关键帧开始（设备推的是 H265，P 帧依赖参考帧）。
     * 此时立刻拉流解码会报 {@code Could not find ref with POC xx}，
     * 解出的是<b>半张好图 + 半张花屏</b>（实测：流上线 0 秒抓 → 下半屏花屏；
     * 预热 2 秒抓 → 完全正常）。
     *
     * <p>预热让 ZLM 攒够一个完整关键帧，之后用 {@code -skip_frame nokey}
     * 只取关键帧，就能拿到可独立解码的完整画面。
     */
    private static final long WARMUP_MS = 2_000;

    /** 抓拍失败时的重试次数（每次重试前多等一个 WARMUP）。 */
    private static final int MAX_ATTEMPTS = 2;

    private final StreamService streamService;
    private final FfmpegSnapService ffmpegSnapService;
    private final SnapService snapService;
    private final DeviceService deviceService;

    /**
     * 抓取通道快照并存盘。
     *
     * @param serial 设备编码
     * @param code   通道编码
     * @return 快照访问路径；失败抛 {@link BizException}
     */
    public String capture(String serial, String code) {
        GbChannel channel = deviceService.findChannel(serial, code);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + serial + "/" + code);
        }

        // 已有会话就复用；没有才点播（结束后要停）
        RtpSession session = streamService.sessionByKey(serial + "/" + code);
        boolean started = false;
        if (session == null) {
            streamService.startLive(serial, code);
            started = true;
        }

        try {
            RtpSession live = awaitOnline(serial, code);
            if (live == null) {
                throw BizException.badRequest("通道流未就绪，无法抓拍：" + code);
            }
            String flvUrl = ffmpegSnapService.localFlvUrl(live);
            byte[] jpeg = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS && jpeg == null; attempt++) {
                // 预热：等 ZLM 攒出完整关键帧，否则会解出花屏
                sleep(WARMUP_MS);
                jpeg = ffmpegSnapService.capture(flvUrl);
                if (jpeg == null) {
                    log.debug("抓拍未成功，重试 {}/{}: serial={} code={}",
                            attempt, MAX_ATTEMPTS, serial, code);
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
     * 等待通道的流上线。
     *
     * <p>点播后设备要经过 INVITE → 200 OK → 推 RTP → ZLM 解出轨道，
     * 实测通常 1~3 秒。
     *
     * @return 已上线的会话；超时返回 {@code null}
     */
    private RtpSession awaitOnline(String serial, String code) {
        long deadline = System.currentTimeMillis() + ONLINE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            RtpSession session = streamService.sessionByKey(serial + "/" + code);
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
