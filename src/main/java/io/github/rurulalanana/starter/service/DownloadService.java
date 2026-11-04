package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Recording;
import io.github.rurulalanana.gb28181.sip.zlm.RecordingService;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备录像下载：把设备侧的录像段录成本地 MP4。
 *
 * <p>不用标准 9.9 的 {@code s=Download}：实测 LiveGBS 与多数国产摄像机对它不应答或回 4xx，
 * 且推过来的仍是 PS/RTP 流。这里走已验证的回放链路 + ZLM 录制，产物是标准 MP4，可断点续传。
 * 任务由服务统一持有（后台执行、自动收尾），不随页面切走 / 刷新而丢；状态放内存，重启后重来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final StreamService streamService;
    private final io.github.rurulalanana.starter.config.Gb28181Properties props;

    /** 进行中的下载任务，key = 会话键（{@code 设备/通道_playback}）。 */
    private final Map<String, Task> active = new ConcurrentHashMap<>();

    /** 已完成（或失败）的任务，key = taskId；只保留最近若干条供界面查看。 */
    private final Map<String, Task> finished = new ConcurrentHashMap<>();

    /** 已完成任务保留条数：再多就没有查看价值，还会白占内存。 */
    private static final int MAX_FINISHED = 20;

    /**
     * 收尾宽限（秒）：在「已耗时 ≥ 段长」之后再多等这么久。
     * 不能给大：实测设备不理会回放 INVITE 的 {@code t=} 结束时间，推完这一段会接着推下一段，
     * 宽限给多了片尾会混进无关画面。ZLM 在 {@code mk_recorder_stop} 时会自己 flush 并写 moov。
     */
    private static final long GRACE_SECONDS = 10L;

    /** 判定设备已停推前至少先跑这么久，避免起播瞬间会话空缺被误判（秒）。 */
    private static final long MIN_RUN_SECONDS = 15L;

    /**
     * 「已落盘字节不再增长」持续这么久即认为推完（秒）。
     * 设备推完不再发 RTP 但会话往往还挂着，只等「墙钟 + 宽限」会让支持倍速的设备白等；
     * 30 秒足以让公网推流的正常抖动不被误判（实测丢包重传最长空窗约 8 秒）。
     */
    private static final long STALL_SECONDS = 30L;

    /** 已完成任务的持久化文件名（放在录像根目录下，重启不丢）。 */
    private static final String PERSIST_FILE = ".download-tasks.json";

    /**
     * 一个下载任务。字段除进度 / 结果外均在构造时确定，外部只能通过 {@link #list()} 拿只读快照。
     */
    public static final class Task {
        final String taskId;
        final String sessionKey;
        final String recordKey;
        final String deviceId;
        final String channelId;
        final String channelName;
        final String startAt;
        final String endAt;
        final long totalSecond;
        /**
         * 请求的设备推流倍速（标准 9.9.1 b) 的 {@code a=downloadspeed}）。
         * 只用于日志与展示，不参与完成判定：多数设备忽略它（实测报 1/2/4/8 但仍是 1 倍速）。
         */
        final int speed;
        /**
         * 该段在设备侧的文件大小（录像检索的 {@code FileSize}），0 表示设备没报。
         * 不能用作完成判据：MP4 比设备侧 PS 小约 31%，{@code dirBytes >= expectedBytes} 永不成立。
         */
        final long expectedBytes;
        final long startedAt = System.currentTimeMillis();
        /** 防止重复收尾（轮询与定时扫描可能同时判定「已录满」）。 */
        final AtomicBoolean finishing = new AtomicBoolean();
        volatile boolean finished;
        volatile boolean ok;
        volatile String message;
        volatile String filePath;
        volatile String fileName;
        volatile String url;
        volatile long fileSize;
        /** 已完成任务的冻结进度（重启恢复时用）；0 = 未冻结。 */
        volatile long frozenSecond;
        /** 上一次观察到的已落盘字节数与时刻（停滞判据用）。 */
        volatile long lastBytes;
        volatile long lastBytesAt;

        Task(String sessionKey, String recordKey, String deviceId, String channelId,
             String channelName, String startAt, String endAt, long totalSecond, int speed,
             long expectedBytes) {
            this.taskId = sessionKey;
            this.sessionKey = sessionKey;
            this.recordKey = recordKey;
            this.deviceId = deviceId;
            this.channelId = channelId;
            this.channelName = channelName;
            this.startAt = startAt;
            this.endAt = endAt;
            this.totalSecond = totalSecond;
            this.speed = Math.max(1, speed);
            this.expectedBytes = Math.max(0L, expectedBytes);
        }

        /** 已录内容秒数：按 1 倍速实时估算，设备真支持倍速时会直接跳到 100% 结束。 */
        long recordedSecond(long now) {
            long elapsed = (now - startedAt) / 1000L;
            return totalSecond > 0 ? Math.min(elapsed, totalSecond) : elapsed;
        }

        /** 已耗时（秒）。 */
        long elapsedSecond(long now) {
            return (now - startedAt) / 1000L;
        }
    }

    /** 下载进度快照（旧接口用）。 */
    public record Progress(long recordedSecond, long totalSecond,
                           boolean finished, long fileSize) {
    }

    /** 任务列表项（界面用）。与 Task 分开，避免把 {@code finishing} 这类内部状态序列化给前端。 */
    public record View(String taskId, String deviceId, String channelId, String channelName,
                       String startAt, String endAt, long recordedSecond, long totalSecond,
                       int percent, int speed, boolean finished, boolean ok,
                       String fileName, String filePath, long fileSize, String url,
                       String message, long elapsedSecond) {
    }

    /** 一个新任务的创建参数。 */
    public record Request(String sessionKey, String deviceId, String channelId,
                          String channelName, String startAt, String endAt,
                          long totalSecond, int speed, long expectedBytes) {
    }

    /**
     * 启动下载：对已建立的回放流开始录制。要求回放流已存在（调用方先走 {@code playback/start}），
     * 这样「边看边下」共用一路流，不会重复 INVITE。
     */
    public String start(String sessionKey, long totalSecond) {
        return start(sessionKey, totalSecond, 1);
    }

    /**
     * 启动下载（兼容旧调用）：只带会话键与时长，元数据留空。
     */
    public String start(String sessionKey, long totalSecond, int speed) {
        return start(new Request(sessionKey, null, null, null, null, null,
                totalSecond, speed, 0L)).taskId();
    }

    /**
     * 启动一个下载任务（后台执行，与发起页面解耦）。
     *
     * @return 任务快照；同一段录像重复请求返回已有任务（不重复录制）
     */
    public View start(Request req) {
        String sessionKey = req.sessionKey();
        if (sessionKey == null || sessionKey.isBlank()) {
            throw BizException.badRequest("缺少回放会话键 streamid");
        }
        Task existing = active.get(sessionKey);
        if (existing != null) {
            // 同一段重复点下载：直接返回已有任务，避免两路录制抢同一个流
            return view(existing, System.currentTimeMillis());
        }
        RtpSession session = streamService.sessionByKey(sessionKey);
        if (session == null) {
            throw BizException.badRequest("回放会话不存在，请先调用 playback/start：" + sessionKey);
        }
        RecordingService recorder = streamService.recordingService();
        if (recorder == null) {
            throw BizException.badRequest("录像服务未启动，无法下载");
        }
        /* 必须等媒体源真正建立再开录：{@code playback/start} 只发出 INVITE，
         * 设备要几百毫秒到数秒才推 RTP，ZLM 收到第一个包才注册媒体源，否则 mk_recorder_start 返回失败。
         */
        awaitStreamReady(sessionKey, STREAM_READY_TIMEOUT_MS);
        String recordKey = session.getSessionKey();
        /*
         * 片长传「段长 + 60 秒」：ZLM 按固定时长切片（默认 3600s），2 小时的录像会被切成 2 个文件，
         * 而索引只保留最后一片 —— 用户拿到半段视频。给足余量才能只产出一个完整文件。
         */
        int maxSecond = (int) Math.min(Integer.MAX_VALUE - 60,
                Math.max(60L, req.totalSecond() + 60L));
        recorder.startRecordingStream(recordKey, session.getZlmApp(), session.getStream(),
                maxSecond);
        Task task = new Task(sessionKey, recordKey,
                req.deviceId() != null ? req.deviceId() : session.getDeviceId(),
                req.channelId() != null ? req.channelId() : session.getChannelId(),
                req.channelName(), req.startAt(), req.endAt(), req.totalSecond(),
                req.speed(), req.expectedBytes());
        active.put(sessionKey, task);
        /* 锁定该通道的回放会话：不锁时用户再点另一段回放会把下载那路顶掉，
         * ZLM 录制随之重置，产物只有开头几十秒且日志无异常。 */
        streamService.lockForDownload(sessionKey);
        log.info("开始下载录制: session={} source={}/{} 通道={} 时长={}s 期望大小={}MB 请求倍速={}x",
                sessionKey, session.getZlmApp(), session.getStream(), task.channelName,
                req.totalSecond(), req.expectedBytes() / 1048576, task.speed);
        return view(task, System.currentTimeMillis());
    }

    /** 等媒体源就绪的总时长上限。 */
    private static final long STREAM_READY_TIMEOUT_MS = 20_000L;

    /** 轮询等待 ZLM 收到该会话的 RTP，超时抛 {@link BizException}。 */
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
        Task task = active.get(sessionKey);
        if (task == null) {
            Task done = finished.get(sessionKey);
            if (done != null) {
                return new Progress(done.recordedSecond(System.currentTimeMillis()),
                        done.totalSecond, true, done.fileSize);
            }
            throw BizException.notFound("下载任务不存在：" + sessionKey);
        }
        long now = System.currentTimeMillis();
        // 完成判定与自动收尾用同一套规则，避免「这里说完成了、那边还在录」
        boolean done = task.finished || shouldFinish(task, now);
        return new Progress(task.recordedSecond(now), task.totalSecond, done, task.fileSize);
    }

    /** 全部任务快照（进行中在前，其余按开始时间倒序），供界面全局下载面板使用。 */
    public List<View> list() {
        long now = System.currentTimeMillis();
        List<View> out = new ArrayList<>();
        List<Task> running = new ArrayList<>(active.values());
        running.sort(Comparator.comparingLong((Task t) -> t.startedAt).reversed());
        for (Task t : running) {
            out.add(view(t, now));
        }
        List<Task> done = new ArrayList<>(finished.values());
        done.sort(Comparator.comparingLong((Task t) -> t.startedAt).reversed());
        for (Task t : done) {
            out.add(view(t, now));
        }
        return out;
    }

    /** 该通道是否有正在进行的下载任务（用于保护回放会话不被停掉）。 */
    public boolean isDownloading(String deviceId, String channelId) {
        for (Task t : active.values()) {
            if (channelId != null && channelId.equals(t.channelId)
                    && (deviceId == null || deviceId.equals(t.deviceId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定时扫描：把该收尾的任务自动收尾。
     * 结束判定用真实信号：回放流关闭（设备推完断开，最准），或墙钟走完段长 + 宽限兜底，
     * 避免任务永不结束。由 {@code PlatformScheduler} 每 5 秒调用。
     */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Task t : active.values()) {
            if (shouldFinish(t, now)) {
                finishAsync(t);
            }
        }
    }

    /**
     * 本次扫描是否应当收尾：回放流已关闭（最准）；墙钟走完段长 + 宽限；
     * 落盘停滞（见 {@link #isStalled}，兼容真正支持倍速的设备）。
     */
    private boolean shouldFinish(Task task, long now) {
        long elapsed = task.elapsedSecond(now);
        if (elapsed < MIN_RUN_SECONDS) {
            return false;
        }
        if (streamGone(task)) {
            return true;
        }
        if (task.totalSecond > 0 && elapsed >= task.totalSecond + GRACE_SECONDS) {
            return true;
        }
        // 设备推完就停、但会话还挂着：靠文件增长停滞来发现
        return isStalled(task, now);
    }

    /**
     * 停滞判据：已落盘字节连续 {@link #STALL_SECONDS} 秒没增长，且已录过「按倍速折算的一半时长」。
     * 不能拿设备上报的 FileSize 直接比：录的是转封装后的 MP4，实测比设备侧 PS 小约 31%，
     * {@code dirBytes >= expectedBytes * 98%} 永远不会成立。
     */
    private boolean isStalled(Task task, long now) {
        if (task.totalSecond <= 0) {
            return false;
        }
        long half = task.totalSecond / 2 / Math.max(1, task.speed);
        if (task.elapsedSecond(now) < Math.max(MIN_RUN_SECONDS, half)) {
            return false;
        }
        long bytes = dirBytes(task.sessionKey);
        if (bytes > task.lastBytes) {
            task.lastBytes = bytes;
            task.lastBytesAt = now;
            return false;
        }
        return task.lastBytesAt > 0
                && task.lastBytes > 0
                && now - task.lastBytesAt >= STALL_SECONDS * 1000L;
    }

    /**
     * 该会话目录下已落盘字节总数（含 {@code .} 前缀临时文件）。
     * 用总和而非单文件：到达 {@code max_second} 时 ZLM 会改名并开新文件，单看一个会出现「归零」假象。
     */
    private long dirBytes(String sessionKey) {
        try {
            java.nio.file.Path dir = sessionDir(sessionKey);
            if (!java.nio.file.Files.isDirectory(dir)) {
                return 0L;
            }
            try (var stream = java.nio.file.Files.walk(dir)) {
                return stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                        .mapToLong(p -> {
                            try {
                                return java.nio.file.Files.size(p);
                            } catch (Exception e) {
                                return 0L;
                            }
                        })
                        .sum();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 该任务对应的回放流是否已经没了（会话不存在或已关闭）。 */
    private boolean streamGone(Task task) {
        try {
            RtpSession session = streamService.sessionByKey(task.sessionKey);
            return session == null || RtpSession.CLOSED.equals(session.getState());
        } catch (Exception e) {
            return false;
        }
    }

    /** 收尾（异步）：停录 + 记录结果，避免阻塞调用方（轮询/调度线程）。 */
    private void finishAsync(Task task) {
        if (!task.finishing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                doFinish(task);
            } catch (Exception e) {
                task.ok = false;
                task.message = e.getMessage() == null ? "收尾失败" : e.getMessage();
                log.warn("下载收尾失败: task={} {}", task.taskId, e.getMessage());
            } finally {
                task.finished = true;
                // 冻结进度，避免重启恢复后进度又从头爬
                task.frozenSecond = task.recordedSecond(System.currentTimeMillis());
                active.remove(task.sessionKey);
                finished.put(task.taskId, task);
                // 无论成败都要解锁，否则这个通道的回放会被永久拒绝
                streamService.unlockDownload(task.sessionKey);
                trimFinished();
                persist();
            }
        });
    }

    /** 已完成任务总量控制（保留最近 {@link #MAX_FINISHED} 条）。 */
    private void trimFinished() {
        if (finished.size() <= MAX_FINISHED) {
            return;
        }
        finished.values().stream()
                .sorted(Comparator.comparingLong((Task t) -> t.startedAt))
                .limit(finished.size() - MAX_FINISHED)
                .map(t -> t.taskId)
                .toList()
                .forEach(finished::remove);
    }

    /**
     * 结束下载并返回产物路径（同步收尾）。后台自动收尾走 {@link #finishAsync}。
     *
     * @return MP4 文件的绝对路径；录制失败返回 {@code null}
     */
    public String finish(String sessionKey) {
        Task task = active.get(sessionKey);
        if (task == null) {
            Task done = finished.get(sessionKey);
            return done == null ? null : done.filePath;
        }
        if (!task.finishing.compareAndSet(false, true)) {
            // 已有收尾在跑（定时扫描抢先了）：等它写回结果
            for (int i = 0; i < 100 && !task.finished; i++) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return task.filePath;
        }
        String path = doFinish(task);
        task.finished = true;
        active.remove(sessionKey);
        finished.put(task.taskId, task);
        trimFinished();
        persist();
        return path;
    }

    /**
     * 真正的收尾动作：停录（让 ZLM flush 完整文件）→ 释放回放流。
     *
     * @return 产出文件路径；失败返回 {@code null}
     */
    private String doFinish(Task task) {
        RecordingService recorder = streamService.recordingService();
        if (recorder != null) {
            /*
             * 【关键】先快照当前路径再停录：ZLM 到 max_second 会自己收尾并回调 on_mk_record_mp4，
             * 此时条目上已有 filePath；而 stopRecording 内部的 awaitFinalSegment() 会先清空路径等新回调，
             * 若文件早已收尾就不会再有回调 —— 路径永远是 null，把「文件已写好」误判成「未产出文件」。
             */
            String before = recordingPath(recorder, task.recordKey);
            Recording rec = recorder.stopRecording(task.recordKey);
            String path = pickPath(rec, before, recordingPath(recorder, task.recordKey),
                    newestFile(task.sessionKey));
            if (path != null) {
                task.filePath = path;
                task.fileName = new File(path).getName();
                task.fileSize = new File(path).length();
                task.ok = true;
                log.info("下载录制完成: task={} file={} size={}KB",
                        task.taskId, task.filePath, task.fileSize / 1024);
            } else {
                task.ok = false;
                task.message = "录制未产出文件，请确认录像时段内设备确实有数据";
                log.warn("下载收尾未拿到文件路径: task={}（已录 {} 秒）",
                        task.taskId, task.elapsedSecond(System.currentTimeMillis()));
            }
        } else {
            task.ok = false;
            task.message = "录像服务未启动";
        }
        RtpSession session = streamService.sessionByKey(task.sessionKey);
        if (session != null) {
            streamService.stop(session.getDeviceId(), session.getChannelId(),
                    io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        }
        return task.filePath;
    }

    /** 依次取第一个非空的文件路径（索引快照 → 停录结果 → 索引复查 → 磁盘扫描）。 */
    private static String pickPath(Recording rec, String... candidates) {
        if (rec != null && notBlank(rec.getFilePath())) {
            return rec.getFilePath();
        }
        for (String c : candidates) {
            if (notBlank(c)) {
                return c;
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 录像索引里该键下最近一个已落盘的条目路径。 */
    private String recordingPath(RecordingService recorder, String recordKey) {
        String best = null;
        long bestStart = Long.MIN_VALUE;
        for (Recording r : recorder.recordings(recordKey)) {
            if (!notBlank(r.getFilePath())) {
                continue;
            }
            if (r.getStartTime() >= bestStart) {
                bestStart = r.getStartTime();
                best = r.getFilePath();
            }
        }
        return best;
    }

    /**
     * 会话的录像目录。必须把 {@code /} 换成 {@code _}：{@code RecordingService} 用
     * {@code safeDir(recordKey)} 建目录，而会话键是 {@code 设备/通道_playback} 形式，
     * 直接 Path.of 会被当成目录分隔符，拼出的目录永不存在，字节判据恒为 0。
     */
    private java.nio.file.Path sessionDir(String sessionKey) {
        return java.nio.file.Path.of(props.resolvedRecordRoot(),
                String.valueOf(sessionKey).replace('/', '_').replace('\\', '_'));
    }

    /**
     * 磁盘兜底：取该会话目录下最大的 mp4，只认非 {@code .} 前缀的正式文件
     * （临时文件还没写 moov，交给用户也打不开）。
     */
    private String newestFile(String sessionKey) {
        try {
            java.nio.file.Path dir = sessionDir(sessionKey);
            if (!java.nio.file.Files.isDirectory(dir)) {
                return null;
            }
            try (var stream = java.nio.file.Files.walk(dir)) {
                return stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".mp4") && !n.startsWith(".");
                        })
                        .max(Comparator.comparingLong(p -> {
                            try {
                                return java.nio.file.Files.size(p);
                            } catch (Exception e) {
                                return 0L;
                            }
                        }))
                        .map(java.nio.file.Path::toString)
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 取消下载（不产出文件）。 */
    public void cancel(String sessionKey) {
        Task task = active.remove(sessionKey);
        if (task == null) {
            return;
        }
        task.finishing.set(true);
        streamService.unlockDownload(sessionKey);
        RecordingService recorder = streamService.recordingService();
        if (recorder != null) {
            recorder.stopRecording(task.recordKey);
        }
        RtpSession session = streamService.sessionByKey(sessionKey);
        if (session != null) {
            streamService.stop(session.getDeviceId(), session.getChannelId(),
                    io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        }
        task.finished = true;
        task.ok = false;
        task.message = "已取消";
        finished.put(task.taskId, task);
        trimFinished();
        persist();
        log.info("下载已取消: task={}", task.taskId);
    }

    /** 从任务列表里移除一条记录（不动磁盘文件）。 */
    public boolean remove(String taskId) {
        boolean removed = finished.remove(taskId) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /** 把内部任务折算成对外的只读快照。 */
    private View view(Task t, long now) {
        long recorded = t.frozenSecond > 0 ? t.frozenSecond : t.recordedSecond(now);
        int percent = t.totalSecond > 0
                ? (int) Math.min(100, recorded * 100 / t.totalSecond)
                : 0;
        /* 已成功完成的任务一律显示 100%：进度按墙钟估，而设备可能在 t= 内容推完就停，
           正常收尾时 recorded 常停在 60%~95%，给用户看「已完成但只有 72%」会以为被截断。 */
        if (t.finished && t.ok) {
            percent = 100;
            recorded = t.totalSecond > 0 ? t.totalSecond : recorded;
        }
        return new View(t.taskId, t.deviceId, t.channelId, t.channelName,
                t.startAt, t.endAt, recorded, t.totalSecond, percent, t.speed,
                t.finished, t.ok, t.fileName, t.filePath, t.fileSize, t.url, t.message,
                t.elapsedSecond(now));
    }

    // ==================== 持久化 ====================

    /**
     * 启动时载入「最近已完成」的任务记录。必须持久化：否则重启后界面上找不到刚下的文件，
     * 且录像地址签名密钥是进程级随机的（见 {@code RecordUrlSigner}），旧链接全部 403。
     * 只存元数据与磁盘路径，不存签名地址 —— 列出时按当前进程密钥重新签名。
     */
    @jakarta.annotation.PostConstruct
    void loadPersisted() {
        java.nio.file.Path p = persistPath();
        if (!java.nio.file.Files.isRegularFile(p)) {
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            Persisted[] arr = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(bytes, Persisted[].class);
            for (Persisted d : arr) {
                if (d == null || d.taskId == null) {
                    continue;
                }
                // 文件被删了就别再列出来（用户点开也是 404）
                if (d.filePath != null && !java.nio.file.Files.exists(
                        java.nio.file.Path.of(d.filePath))) {
                    continue;
                }
                Task t = new Task(d.taskId, d.recordKey, d.deviceId, d.channelId,
                        d.channelName, d.startAt, d.endAt, d.totalSecond, d.speed,
                        d.expectedBytes);
                t.finished = true;
                t.ok = d.ok;
                t.message = d.message;
                t.filePath = d.filePath;
                t.fileName = d.fileName;
                t.fileSize = d.fileSize;
                /* 冻结进度：否则重启后 recordedSecond 从 0 重新爬升（按「从任务开始到现在」算），
                   界面上已完成的任务会像还在下载。 */
                t.frozenSecond = d.recordedSecond;
                finished.put(t.taskId, t);
            }
            log.info("已恢复 {} 个历史下载任务记录", finished.size());
        } catch (Exception e) {
            log.warn("读取下载任务记录失败（忽略，不影响使用）: {}", e.getMessage());
        }
    }

    /** 把已完成任务写盘（失败只告警，不影响主流程）。 */
    private void persist() {
        try {
            Persisted[] arr = finished.values().stream()
                    .sorted(Comparator.comparingLong((Task t) -> t.startedAt).reversed())
                    .map(Persisted::of)
                    .toArray(Persisted[]::new);
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsBytes(arr);
            java.nio.file.Path p = persistPath();
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.write(p, bytes);
        } catch (Exception e) {
            log.warn("保存下载任务记录失败（不影响下载）: {}", e.getMessage());
        }
    }

    private java.nio.file.Path persistPath() {
        return java.nio.file.Path.of(props.resolvedRecordRoot(), PERSIST_FILE);
    }

    /** 持久化用的扁平结构（不放 Task，避免把 AtomicBoolean 等内部状态写进文件）。 */
    @lombok.Data
    public static class Persisted {
        private String taskId;
        private String recordKey;
        private String deviceId;
        private String channelId;
        private String channelName;
        private String startAt;
        private String endAt;
        private long totalSecond;
        private int speed;
        private long expectedBytes;
        private long recordedSecond;
        private boolean ok;
        private String message;
        private String filePath;
        private String fileName;
        private long fileSize;

        static Persisted of(Task t) {
            Persisted d = new Persisted();
            d.taskId = t.taskId;
            d.recordKey = t.recordKey;
            d.deviceId = t.deviceId;
            d.channelId = t.channelId;
            d.channelName = t.channelName;
            d.startAt = t.startAt;
            d.endAt = t.endAt;
            d.totalSecond = t.totalSecond;
            d.speed = t.speed;
            d.expectedBytes = t.expectedBytes;
            d.recordedSecond = t.frozenSecond > 0
                    ? t.frozenSecond
                    : Math.min(t.totalSecond, (System.currentTimeMillis() - t.startedAt) / 1000L);
            d.ok = t.ok;
            d.message = t.message;
            d.filePath = t.filePath;
            d.fileName = t.fileName;
            d.fileSize = t.fileSize;
            return d;
        }
    }
}
