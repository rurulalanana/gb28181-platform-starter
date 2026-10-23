package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** 抓拍失败时的重试次数。 */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 每次重试额外增加的预热时长（毫秒）。
     *
     * <p>失败通常是因为「流刚建立、还没攒出关键帧」，多等一会儿就能成功。
     * 递增而不是固定：第一次几乎不用等，后面逐步给足时间，
     * 总时长（2s + 3.5s + 5s ≈ 10.5s）仍在可接受的接口等待范围内。
     */
    private static final long RETRY_EXTRA_MS = 1_500;

    /**
     * 同时抓拍的并发上限。
     *
     * <p><b>为什么必须限流</b>：前端的「进入列表自动补抓」会一次性请求所有
     * 没有快照的通道。每个请求都要点播一路流（H265 约 3Mbps），
     * 10 个通道同时抓就是 30Mbps —— 设备上行带宽根本撑不住，
     * 结果全部失败（而且设备可能因此把已正常的流也抖掉）。
     *
     * <p>取 2：一台设备通常能同时推 1~2 路，再多就是互相拖累。
     * 拿不到许可时快速失败（前端会显示「稍后重试」），而不是排队等到超时 ——
     * 排队会让用户以为界面卡死。
     */
    private static final int MAX_CONCURRENT = 2;

    /**
     * 会话已存在但非 ONLINE 时，多久才允许重建（毫秒）。
     *
     * <p>会话处于 NEGOTIATING 说明 INVITE 已发、设备正在推流。
     * 立刻重建会打断它；但一直等下去也拿不到图（实测有会话卡在
     * NEGOTIATING 长达十几分钟）。给 8 秒窗口，超过就认为卡死、重建一次。
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
        // 决定是否需要（重新）点播。
        //
        // 【原实现的缺陷】只在「压根没有会话」时才点播。而实际最常见的是
        // 「有会话但卡在 NEGOTIATING」（设备没推流、或上次 INVITE 丢了）——
        // 此时旧代码 started=false、不点播，然后白等 25 秒超时失败，
        // 用户看到的是「抓拍失败」而其实只要重建一次就能拍。
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
            // startLive 内部会区分「可复用的活会话」与「死会话」：
            // 后者它自己会先关掉再 INVITE，不会留下僵尸会话。
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
                // 【降级策略】前两次要求关键帧（画面完整），
                // 最后一次放开限制 —— 流刚建立时可能几秒内一个关键帧都没有
                // （实测 GOP 2 秒但设备推流初期有延迟），
                // 此时「可能花屏的图」也比「抓拍失败」有用得多。
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
     * 等待通道的流上线。
     *
     * <p>点播后设备要经过 INVITE → 200 OK → 推 RTP → ZLM 解出轨道，
     * 实测通常 1~3 秒。
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
