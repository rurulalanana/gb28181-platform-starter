package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SDK 直连设备的录像下载。
 *
 * <h3>为什么不能用现有的「录回放流」下载</h3>
 * {@code DownloadService} 的实现是「拉一路回放流 → 让 ZLM 录下来」，
 * 那适合国标（设备只推流给你）。但大华可以直接把<b>设备上的原始录像文件</b>
 * 按块拉下来，优点很明显：
 * <ul>
 *   <li>拿到的是原始文件，不是重新编码的流 —— 时间戳、画质、码率都不变，可用于取证</li>
 *   <li>不需要拉流、不需要设备为下载额外推一路（省设备和上行带宽）</li>
 *   <li>下载期间不影响别人看实时画面</li>
 * </ul>
 * 所以只要设备支持 SDK，就必须走这条，不能复用「录流」那条路。
 *
 * <h3>为什么是异步任务</h3>
 * 一段 2 小时的录像可能几百 MB，同步等会占死 HTTP 线程并让反向代理超时。
 * 任务状态放内存（与 {@code DownloadService} 的口径一致）：进程重启后任务丢失，
 * 但设备上的录像还在，用户可以重新发起 —— 这比维护一套持久化任务表划算。
 */
@Slf4j
@Service
public class DahuaDownloadService {

    /** 同时下载任务上限：每条都占设备一个下载会话，太多会被设备拒绝。 */
    private static final int MAX_CONCURRENT = 3;

    /** 单任务最长等待：2 小时。超时停止并保留已下载部分。 */
    private static final long TASK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    private final DahuaCapabilityService dahuaCapability;
    private final Gb28181Properties props;

    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
        Thread t = new Thread(r, "dahua-download");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public DahuaDownloadService(DahuaCapabilityService dahuaCapability,
                                Gb28181Properties props) {
        this.dahuaCapability = dahuaCapability;
        this.props = props;
    }

    /**
     * 发起一次下载。
     *
     * @param start 起始时间 {@code yyyy-MM-dd HH:mm:ss}
     * @param end   结束时间
     * @return 任务快照（立即返回，实际下载在后台）
     */
    public TaskView start(String deviceId, String channelCode, String start, String end) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Path.of(props.resolvedRecordRoot(), "download", sanitize(deviceId));
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw BizException.badRequest("创建下载目录失败：" + e.getMessage());
        }
        Path target = dir.resolve(sanitize(channelCode) + "-" + stamp + ".dav");
        Task task = new Task(taskId, deviceId, channelCode, start, end, target);
        tasks.put(taskId, task);

        pool.submit(() -> runDownload(task, session, sdkChannel, start, end));
        log.info("大华录像下载已排队: task={} device={} channel={} {} ~ {} -> {}",
                taskId, deviceId, channelCode, start, end, target);
        return task.view();
    }

    private void runDownload(Task task, io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
                             int sdkChannel, String start, String end) {
        task.state = "RUNNING";
        try {
            long bytes = dahuaCapability.ext().downloadRecord(session, sdkChannel, start, end,
                    task.target, TASK_TIMEOUT_MS,
                    p -> {
                        task.downloaded = p.downloadedBytes();
                        task.total = p.totalBytes();
                    });
            task.bytes = bytes;
            task.state = "DONE";
            task.finishedAt = LocalDateTime.now();
            log.info("大华录像下载完成: task={} {} 字节 -> {}", task.taskId, bytes, task.target);
        } catch (DahuaSdkException e) {
            task.state = "FAILED";
            task.message = e.getMessage()
                    + (e.hint() == null || e.hint().isBlank() ? "" : "（" + e.hint() + "）");
            task.finishedAt = LocalDateTime.now();
            log.warn("大华录像下载失败: task={} {}", task.taskId, task.message);
        } catch (Exception e) {
            task.state = "FAILED";
            task.message = e.toString();
            task.finishedAt = LocalDateTime.now();
            log.warn("大华录像下载异常: task={}", task.taskId, e);
        }
    }

    /** 任务列表（最近的在前）。 */
    public List<TaskView> list() {
        List<TaskView> out = new ArrayList<>();
        List<Task> sorted = new ArrayList<>(tasks.values());
        sorted.sort(Comparator.comparing((Task t) -> t.createdAt).reversed());
        for (Task t : sorted) {
            out.add(t.view());
        }
        return out;
    }

    /** 单任务状态。 */
    public TaskView get(String taskId) {
        Task t = tasks.get(taskId);
        if (t == null) {
            throw BizException.notFound("下载任务不存在：" + taskId);
        }
        return t.view();
    }

    /** 置为已处理（从列表移除，不影响已下载文件）。 */
    public boolean remove(String taskId) {
        Task t = tasks.remove(taskId);
        return t != null;
    }

    /** 文件名安全化：设备/通道编码里可能有 {@code /} 或冒号，直接当文件名会报错。 */
    private static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** 内部任务状态（可变字段，工人线程写、接口线程读）。 */
    private static final class Task {
        private final String taskId;
        private final String deviceId;
        private final String channelCode;
        private final String start;
        private final String end;
        private final Path target;
        private final LocalDateTime createdAt = LocalDateTime.now();
        private volatile String state = "PENDING";
        private volatile long downloaded;
        private volatile long total;
        private volatile long bytes;
        private volatile String message;
        private volatile LocalDateTime finishedAt;

        Task(String taskId, String deviceId, String channelCode,
             String start, String end, Path target) {
            this.taskId = taskId;
            this.deviceId = deviceId;
            this.channelCode = channelCode;
            this.start = start;
            this.end = end;
            this.target = target;
        }

        TaskView view() {
            AtomicReference<String> path = new AtomicReference<>(target.toString());
            return new TaskView(taskId, deviceId, channelCode, start, end,
                    state, downloaded, total, bytes, path.get(), message,
                    createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    finishedAt == null ? null
                            : finishedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }

    /** 下载任务快照（接口返回体）。 */
    public record TaskView(String taskId, String deviceId, String channelCode,
                           String startTime, String endTime, String state,
                           long downloadedBytes, long totalBytes, long fileBytes,
                           String filePath, String message,
                           String createdAt, String finishedAt) {
    }
}
