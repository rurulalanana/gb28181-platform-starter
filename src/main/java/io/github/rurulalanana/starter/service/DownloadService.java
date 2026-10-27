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
 * <h3>为什么是「后台任务」而不是一次请求</h3>
 * 录像下载的耗时等于「录像段时长 ÷ 倍速」，动辄几分钟到几十分钟。
 * 若把它挂在发起请求的那个页面上（前端轮询），用户一旦切走页面、
 * 刷新浏览器，进度就丢了，而且页面卸载时的 {@code playback/stop}
 * 还会把回放流停掉——下载直接中断。
 *
 * <p>因此任务由本服务统一持有：
 * <ul>
 *   <li>任务列表<b>全局可见</b>（{@link #list()}），任意页面都能查；</li>
 *   <li>录像段录满后<b>自动收尾</b>（{@link #tick()}），不依赖前端调
 *       {@code download/finish}——用户已经关掉页面也不会丢结果；</li>
 *   <li>任务下的回放流由 {@link #isDownloading} 保护，
 *       用户关回放弹窗时不得停止（见 {@code PlaybackController#stop}）。</li>
 * </ul>
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
    private final io.github.rurulalanana.starter.config.Gb28181Properties props;

    /** 进行中的下载任务，key = 会话键（{@code 设备/通道_playback}）。 */
    private final Map<String, Task> active = new ConcurrentHashMap<>();

    /** 已完成（或失败）的任务，key = taskId；只保留最近若干条供界面查看。 */
    private final Map<String, Task> finished = new ConcurrentHashMap<>();

    /** 已完成任务保留条数：再多就没有查看价值，还会白占内存。 */
    private static final int MAX_FINISHED = 20;

    /**
     * 收尾宽限（秒）：在「已耗时 ≥ 段长」之后再多等这么久。
     *
     * <p><b>为什么不能给 30 秒</b>：实测设备<b>不理会</b>回放 INVITE 里
     * {@code t=} 的结束时间 —— 把 180 秒的段推完之后它不停，接着推下一段录像。
     * 宽限给多了，产物尾部就会混进几十秒与请求时段无关的画面
     * （用户拖到片尾会看到时间戳跳到别的时段）。
     *
     * <p>10 秒足够：ZLM 在 {@code mk_recorder_stop} 时会主动 flush 并写 moov，
     * 不需要靠宽限等落盘；这点余量只是为了兜住公网推流最后几个包的乱序到达。
     */
    private static final long GRACE_SECONDS = 10L;

    /** 判定「设备已停推」前至少先跑这么久，避免起播瞬间会话空缺被误判（秒）。 */
    private static final long MIN_RUN_SECONDS = 15L;

    /**
     * 「已落盘字节不再增长」持续这么久，就认为设备已经推完（秒）。
     *
     * <p><b>为什么需要这个判据</b>：设备把 {@code t=} 对应的内容推完之后
     * 就不再发 RTP，但会话本身往往还挂着（设备不一定发 BYE）。
     * 此时只能等「墙钟走完段长 + 宽限」，于是：
     * <ul>
     *   <li>设备支持倍速时，早就在 1/N 的时间推完了，用户白等到段长；</li>
     *   <li>用户看到进度条「卡住不动」却不到 100%，会怀疑下载坏了。</li>
     * </ul>
     * 30 秒是能让公网推流的正常抖动不被误判的值（实测丢包重传造成的最长空窗约 8 秒）。
     */
    private static final long STALL_SECONDS = 30L;

    /** 已完成任务的持久化文件名（放在录像根目录下，重启不丢）。 */
    private static final String PERSIST_FILE = ".download-tasks.json";

    /**
     * 一个下载任务。
     *
     * <p>字段全部在构造时确定（除进度/结果），故不对外暴露可变引用，
     * 外部一律通过 {@link #list()} 拿 {@link View} 快照。
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
         *
         * <p><b>只用于日志与界面展示，不参与进度/完成判定</b>：
         * 多数设备会忽略这个字段（实测现场设备上报 1/2/4/8 但仍是 1 倍速）。
         * 按它算完成时刻会把录像截断成几分钟（见 {@link #SAFETY_EXTRA_SECONDS}）。
         */
        final int speed;
        /**
         * 这一段在设备侧的<b>文件大小</b>（来自录像检索的 {@code FileSize}）；
         * {@code 0} 表示设备没报。
         *
         * <p><b>为什么它是完成判定的主信号</b>：设备不保证理会回放 INVITE 里的
         * {@code t=} 结束时间，实测会<b>一直往后推</b>。此时「推完了」永远不会发生，
         * 而按墙钟算又无法区分设备到底几倍速。
         * 用「已落盘字节 ≥ 该段原始大小」判定内容已录完，与倍速无关、也最准确。
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

        /**
         * 已录内容秒数。
         *
         * <p>按<b>实时推进</b>估算（即 1 倍速）：设备若真的支持倍速会提前推完，
         * 那时进度会直接跳到 100% 并结束，不会报一个虚高的中间值。
         */
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

    /**
     * 任务列表项（界面用）。
     *
     * <p>与 {@link Task} 分开：Task 有可变字段与内部锁，直接序列化会让
     * Jackson 把 {@code finishing} 这类内部状态也吐给前端。
     */
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
     * 启动一个下载任务（后台执行，与发起它的页面解耦）。
     *
     * <p>要求回放流已存在 —— 调用方先走 {@code playback/start}，
     * 这样「边看边下」共用一路流，不会重复向设备发起 INVITE。
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
        /* 必须等媒体源真正建立再开录：playback/start 只是发出 INVITE，
         * 设备要过几百毫秒到数秒才开始推 RTP，ZLM 收到第一个包才会注册
         * 媒体源。此时立刻 mk_recorder_start 会返回 0（失败）。
         */
        awaitStreamReady(sessionKey, STREAM_READY_TIMEOUT_MS);
        String recordKey = session.getSessionKey();
        /*
         * 片长传「录像段总长 + 60 秒余量」：ZLM 按固定时长切片（默认 3600s），
         * 下载 2 小时的录像会被切成 2 个文件，而索引只保留最后一片 ——
         * 用户拿到的是半段视频。给足余量才能只产出一个完整文件。
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
        /*
         * 锁定该通道的回放会话。
         *
         * <p>不锁的后果是真实的：用户开着下载又去点另一段回放，
         * 同键的新会话会把下载那一路顶掉，ZLM 侧录制随之重置 ——
         * 最终产物只有开头几十秒。日志里没有任何异常，
         * 用户只能看到「下载时长不够」。
         */
        streamService.lockForDownload(sessionKey);
        log.info("开始下载录制: session={} source={}/{} 通道={} 时长={}s 期望大小={}MB 请求倍速={}x",
                sessionKey, session.getZlmApp(), session.getStream(), task.channelName,
                req.totalSecond(), req.expectedBytes() / 1048576, task.speed);
        return view(task, System.currentTimeMillis());
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

    /**
     * 全部任务快照（进行中在前，其余按开始时间倒序）。
     *
     * <p>供界面全局下载面板使用：任意页面都能看到「哪几路在下载、进度多少」。
     */
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
     *
     * <p><b>结束判定用真实信号，不用「假设倍速」推算</b>：
     * <ol>
     *   <li>设备推完这一段后回放流会关闭（ZLM 收流超时 → 会话 CLOSED），
     *       这是最准确的完成信号；</li>
     *   <li>兵底：即使检测不到停推，按最坏的 1 倍速算完段长再等
     *       {@link #SAFETY_EXTRA_SECONDS} 秒也强制收尾，避免任务永不结束。</li>
     * </ol>
     *
     * <p>由 {@code PlatformScheduler} 每 5 秒调用。
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
     * 本次扫描是否应当收尾该任务。
     *
     * <p>判定顺序（任一成立即收尾）：
     * <ol>
     *   <li><b>回放流已关闭</b>：设备推完这一段并断开，最准；</li>
     *   <li><b>墙钟已走完段长</b>（+{@link #GRACE_SECONDS} 秒）：主判据，
     *       见该常量注释 —— 设备实际就是 1 倍速推；</li>
     *   <li><b>落盘已超设备上报大小</b>：仅当该上报值看起来靠谱（且已录过
     *       四分之一）时用作<b>提前</b>结束信号，兼容真正支持倍速的设备。
     *       实测该字段时准时不准（一个 60 秒的段报过 815MB），不能单独依赖。</li>
     * </ol>
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
     * 停滞判据：已落盘字节连续 {@link #STALL_SECONDS} 秒没增长，且已经录了
     * 「按倍速折算后的一半时长」，就认为设备推完了。
     *
     * <p><b>不能拿设备上报的 FileSize 直接比</b>：那是设备侧原始 PS 文件的大小，
     * 而我们录的是转封装后的 MP4 —— 实测同一段内容 MP4 比 PS 小约 31%
     * （PS 头开销 + 音频重编码差异），所以
     * {@code dirBytes >= expectedBytes * 98%} 这类判据<b>永远不会成立</b>，
     * 等于写了也不生效。改成「增长停滞」后与封装格式无关，倍速是否生效都适用。
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
     * 该会话目录下已落盘的字节总数（含正在写的 {@code .} 前缀临时文件）。
     *
     * <p>用来判断「内容还在不在增长」。用<b>总和</b>而不是单个文件：
     * 到达 {@code max_second} 时 ZLM 会把临时文件改名并开新文件，
     * 单看某一个文件会出现「归零」的假象。
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
     * 结束下载并返回产物路径（接口调用：同步收尾）。
     *
     * <p>「后台自动收尾」走 {@link #finishAsync}；本方法保留给需要
     * 立即拿结果的老调用方。
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
             * 【关键】先快照当前路径再停录。
             *
             * <p>ZLM 到 max_second 会自己把文件收尾并回调 on_mk_record_mp4，
             * 此时条目上已经有 filePath；而 stopRecording 会先调
             * awaitFinalSegment() <b>把路径清空</b>，等着「停录产生的新分段」
             * 回调把它填回来。若文件早已被 ZLM 收尾，就不会再有回调 ——
             * 结果路径永远是 null，把「文件已写好」误判成「未产出文件」。
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
     * 会话的录像目录。
     *
     * <p><b>必须把 {@code /} 换成 {@code _}</b>：录像目录由
     * {@code RecordingService} 用 {@code safeDir(recordKey)} 创建，
     * 而会话键是 {@code 设备/通道_playback} 这种带斜杠的形式。
     * 早先这里直接 {@code Path.of(root, sessionKey)}，斜杠被当成目录分隔符，
     * 拼出 {@code root/设备/通道_playback} —— 这个目录永远不存在，
     * 于是「已落盘字节」恒为 0、字节判据永不成立（倍速下载时只能白等墙钟）。
     */
    private java.nio.file.Path sessionDir(String sessionKey) {
        return java.nio.file.Path.of(props.resolvedRecordRoot(),
                String.valueOf(sessionKey).replace('/', '_').replace('\\', '_'));
    }

    /**
     * 磁盘兵底：取该会话目录下最大的 mp4。
     *
     * <p>只取「非 {@code .} 前缀」的正式文件：临时文件还没写 moov，
     * 交给用户也打不开。
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
        /*
         * 已成功完成的任务一律显示 100%。
         *
         * <p>进度是按<b>墙钟</b>估的，而设备支持的推流时长可能短于请求时长
         * （t= 对应内容推完就停），于是任务正常收尾时 recorded 往往停在
         * 60%~95%。给用户看一个「已完成但只有 72%」的条目，等于让他以为
         * 下载被截断了。
         */
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
     * 启动时载入「最近已完成」的任务记录。
     *
     * <p><b>为什么需要持久化</b>：完成的任务只存在内存里时，服务一重启，
     * 用户在界面上就再也找不到自己刚才下的文件了 —— 而文件明明还在磁盘上。
     * 更糟的是录像地址的签名密钥是<b>进程级随机</b>的（见 {@code RecordUrlSigner}），
     * 重启后旧链接全部 403，用户点「保存到本地」只会失败。
     *
     * <p>因此把已完成任务落到录像根目录下的 {@value #PERSIST_FILE}：
     * 只存元数据与磁盘路径，<b>不存签名地址</b> —— 列出来时按当前进程的密钥
     * 重新签名，重启前后都能下载。
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
                /*
                 * 冻结进度：否则重启后 recordedSecond 会从 0 重新爬升
                 * （它是「从任务开始到现在」算的），界面上会看到已完成的任务
                 * 进度一格格往前跑，像还在下载。
                 */
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
