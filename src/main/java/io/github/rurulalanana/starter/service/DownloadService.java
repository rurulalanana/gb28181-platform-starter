package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Recording;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备录像下载：把设备侧的录像段<b>录制成本地 MP4</b> 供用户下载。
 *
 * <h3>为什么不直接用 {@code s=Download} 的 INVITE</h3>
 * 标准 9.9 确实定义了 {@code s=Download}（设备把文件按文件协议推过来），
 * 但它有两个现实问题：
 * <ol>
 *   <li><b>设备支持度差</b>：实测 LiveGBS 与多数国产摄像机对
 *       {@code s=Download} 直接不应答或回 4xx，它们只实现了回放；</li>
 *   <li><b>产出不是标准 MP4</b>：即便推过来也是 PS/RTP 流，
 *       还要我们自己封装成可下载文件，等于绕一圈。</li>
 * </ol>
 * 而回放链路已经验证稳定（同一台设备、同一个通道），
 * 于是「下载」= <b>用回放拉流 + ZLM 录制成 MP4</b>。
 * 好处是产物就是标准 MP4，支持 Range 断点续传，浏览器直接下载。
 *
 * <h3>状态为什么放内存</h3>
 * 下载任务是「用户点一下、等待、取文件」的短生命周期操作，
 * 重启后重来即可，没必要落库；而录制本身由 {@link Recording} 索引管理。
 * 若需要跨重启续传，另行扩展即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final StreamService streamService;

    /** 进行中的下载任务，key = 会话键（{@code 设备/通道_playback}）。 */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /**
     * 一个下载任务。
     *
     * @param sessionKey  回放会话键
     * @param recordKey   录制索引键（与直播录制区分）
     * @param totalSecond 录像段总时长（秒）
     * @param startedAt   开始时间戳
     */
    public static final class Task {
        final String sessionKey;
        final String recordKey;
        final long totalSecond;
        final long startedAt = System.currentTimeMillis();
        volatile boolean finished;
        volatile String filePath;
        volatile long fileSize;

        Task(String sessionKey, String recordKey, long totalSecond) {
            this.sessionKey = sessionKey;
            this.recordKey = recordKey;
            this.totalSecond = totalSecond;
        }
    }

    /** 下载进度快照。 */
    public record Progress(long recordedSecond, long totalSecond,
                           boolean finished, long fileSize) {
    }

    /**
     * 启动下载：对已建立的回放流开始录制。
     *
     * <p>要求回放流已存在 —— 调用方先走 {@code playback/start}，
     * 这样「边看边下」共用一路流，不会重复向设备发起 INVITE。
     *
     * @param sessionKey 回放会话键（{@code playback/start} 返回的 streamId）
     * @param totalSecond 录像段时长，用于算进度
     * @return 会话键
     */
    public String start(String sessionKey, long totalSecond) {
        RtpSession session = streamService.sessionByKey(sessionKey);
        if (session == null) {
            throw BizException.badRequest("回放会话不存在，请先调用 playback/start：" + sessionKey);
        }
        if (tasks.containsKey(sessionKey)) {
            return sessionKey;
        }
        io.github.rurulalanana.gb28181.sip.zlm.RecordingService recorder =
                streamService.recordingService();
        if (recorder == null) {
            throw BizException.badRequest("录像服务未启动，无法下载");
        }
        /*
         * 必须等媒体源真正建立再开录。
         *
         * <p>{@code playback/start} 只是发出 INVITE，设备要过几百毫秒到数秒
         * 才开始推 RTP，ZLM 收到第一个包才会注册媒体源。此时立刻
         * {@code mk_recorder_start} 会返回 0（失败）——
         * 表现为用户点下载后立刻报「ZLM 启动录制失败，返回码 0」。
         * 用「ZLM 是否已收到过 RTP」而不是「会话是否存在」做判据：
         * 会话在 INVITE 发出后立刻就有，那时媒体源还完全不存在。
         */
        awaitStreamReady(sessionKey, STREAM_READY_TIMEOUT_MS);
        String recordKey = session.getSessionKey();
        /*
         * 按明确的 app/stream 录制：回放流的 stream 名带 _playback 后缀，
         * 用 startRecording(通道编码) 会找不到或录到直播流上。
         *
         * 片长传「录像段总长 + 60 秒余量」：ZLM 按固定时长切片（默认 3600s），
         * 下载 2 小时的录像会被切成 2 个文件，而索引只保留最后一片 ——
         * 用户拿到的是半段视频。给足余量才能只产出一个完整文件。
         */
        int maxSecond = (int) Math.min(Integer.MAX_VALUE - 60,
                Math.max(60L, totalSecond + 60L));
        recorder.startRecordingStream(recordKey, session.getZlmApp(), session.getStream(),
                maxSecond);
        tasks.put(sessionKey, new Task(sessionKey, recordKey, totalSecond));
        log.info("开始下载录制: session={} source={}/{} 时长={}s",
                sessionKey, session.getZlmApp(), session.getStream(), totalSecond);
        return sessionKey;
    }

    /** 等媒体源就绪的总时长上限。 */
    private static final long STREAM_READY_TIMEOUT_MS = 20_000L;

    /**
     * 轮询等待 ZLM 收到该会话的 RTP。
     *
     * @throws BizException 超时仍未就绪
     */
    private void awaitStreamReady(String sessionKey, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (streamService.isReceiving(sessionKey)) {
                return;
            }
            RtpSession s = streamService.sessionByKey(sessionKey);
            if (s == null || RtpSession.CLOSED.equals(s.getState())) {
                throw BizException.badRequest("回放流已关闭，无法下载");
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BizException.badRequest("下载被中断");
            }
        }
        throw BizException.badRequest(
                "等待设备推流超时（" + (timeoutMs / 1000) + " 秒），该时段设备可能没有录像数据");
    }

    /** 查询下载进度。 */
    public Progress progress(String sessionKey) {
        Task task = tasks.get(sessionKey);
        if (task == null) {
            throw BizException.notFound("下载任务不存在：" + sessionKey);
        }
        long elapsed = (System.currentTimeMillis() - task.startedAt) / 1000L;
        // 录制不会超过录像段本身的总时长，到点即视为完成
        long recorded = task.totalSecond > 0
                ? Math.min(elapsed, task.totalSecond) : elapsed;
        boolean done = task.finished || (task.totalSecond > 0 && recorded >= task.totalSecond);
        return new Progress(recorded, task.totalSecond, done, task.fileSize);
    }

    /**
     * 结束下载并返回产物路径。
     *
     * <p>会先停录制（ZLM 需要 flush 才能产出完整文件），再停回放释放设备带宽。
     *
     * @return MP4 文件的绝对路径；录制失败返回 {@code null}
     */
    public String finish(String sessionKey) {
        Task task = tasks.remove(sessionKey);
        if (task == null) {
            throw BizException.notFound("下载任务不存在：" + sessionKey);
        }
        String path = null;
        io.github.rurulalanana.gb28181.sip.zlm.RecordingService recorder =
                streamService.recordingService();
        if (recorder != null) {
            Recording rec = recorder.stopRecording(task.recordKey);
            if (rec != null && rec.getFilePath() != null) {
                path = rec.getFilePath();
                long size = new File(path).length();
                task.fileSize = size;
                task.filePath = path;
                log.info("下载录制完成: session={} file={} size={}KB",
                        sessionKey, path, size / 1024);
            }
        }
        RtpSession session = streamService.sessionByKey(sessionKey);
        if (session != null) {
            streamService.stop(session.getDeviceId(), session.getChannelId(),
                    io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        }
        task.finished = true;
        return path;
    }

    /** 取消下载（不产出文件）。 */
    public void cancel(String sessionKey) {
        Task task = tasks.remove(sessionKey);
        if (task == null) {
            return;
        }
        io.github.rurulalanana.gb28181.sip.zlm.RecordingService recorder =
                streamService.recordingService();
        if (recorder != null) {
            recorder.stopRecording(task.recordKey);
        }
        RtpSession session = streamService.sessionByKey(sessionKey);
        if (session != null) {
            streamService.stop(session.getDeviceId(), session.getChannelId(),
                    io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        }
    }
}
