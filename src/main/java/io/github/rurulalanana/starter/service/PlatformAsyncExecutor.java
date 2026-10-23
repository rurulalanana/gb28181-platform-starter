package io.github.rurulalanana.starter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台异步任务执行器。
 *
 * <p>SIP 回调运行在协议栈线程上，<b>禁止阻塞</b>（阻塞会导致 SIP 重传与超时）；
 * 但落库是 I/O 操作。因此把「回调 → 落库」这一段放到队列里异步执行。
 *
 * <h3>两条独立队列，不要混用</h3>
 *
 * <p>早先所有任务共用一个单线程，实测有问题：归档路径
 * （{@link RecordService#archiveRecording}）内部会<b>轮询等 3 秒</b>等 ZLM
 * 写终态（{@code awaitTerminalRecording}）。10 路通道同时掉线就是 30 秒，
 * 这期间所有设备的心跳、状态落库全被堵在队列里 ——
 * 表现为「设备离线了但界面还是在线」。
 *
 * <p>现在拆成两条：
 * <ul>
 *   <li>{@link #run}：轻量落库（心跳 / 状态 / 目录），单线程<b>保序</b>
 *       —— 同一设备的事件必须按序处理，多线程会状态竞争；</li>
 *   <li>{@link #runArchive}：录像归档。每路通道独立、可并行，且耗时长，
 *       绝不能占用保序队列。</li>
 * </ul>
 *
 * <p><b>两条队列都有界</b>（默认 2000 / 200）。原来用
 * {@code Executors.newSingleThreadExecutor()} 是<b>无界</b>队列：
 * 一旦下游阻塞（比如 ZLM 卡死导致每个任务都等满超时），队列会无限膨胀
 * 直到 OOM。有界 + 拒绝策略至少能保住进程，并留下可观测的日志。
 */
@Slf4j
@Component
public class PlatformAsyncExecutor {

    /**
     * 保序队列容量。
     *
     * <p>正常水位是几十条（心跳 5 分钟一次 × 设备数）。给到 2000 是为了容忍
     * 突发：平台重启后设备集中注册，几百台 × (注册 + 心跳 + 目录 + 状态)
     * 会瞬时涌入。真打满说明下游已经堵死，此时丢弃比 OOM 好。
     */
    private static final int PERSIST_QUEUE_CAPACITY = 2000;

    /** 归档队列容量：归档是秒级任务，正常并发只有几路。 */
    private static final int ARCHIVE_QUEUE_CAPACITY = 200;

    /** 复用的线程工厂：守护线程 + 可读名，便于 jstack 定位。 */
    private static Thread daemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** 落库队列（单线程保序）。 */
    private final ExecutorService persistExecutor =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(PERSIST_QUEUE_CAPACITY),
                    r -> daemonThread(r, "gb28181-persist"),
                    new RejectLogger("落库"));

    /**
     * 归档队列（2 线程并行）。
     *
     * <p>为什么是 2：归档主要耗时在「等 ZLM 写终态」（最多 3 秒）+ 一次 INSERT。
     * 2 个线程足够覆盖多路同时掉线，又不至于让并发事务数过多。
     */
    private final ExecutorService archiveExecutor =
            new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(ARCHIVE_QUEUE_CAPACITY),
                    r -> daemonThread(r, "gb28181-archive"),
                    new RejectLogger("归档"));

    /** 因队列满被丢弃的任务数（可观测性：持续增长说明下游堵了）。 */
    private final AtomicLong rejected = new AtomicLong();

    /**
     * 提交一个保序异步任务；任务内部异常只记录日志，不影响调用方。
     *
     * <p><b>保序</b>：单线程 FIFO，同一设备的注册 / 心跳 / 目录按提交顺序执行。
     *
     * @param name 任务名（日志用）
     * @param task 任务体
     */
    public void run(String name, Runnable task) {
        submit(persistExecutor, name, task);
    }

    /**
     * 提交一个录像归档任务（独立队列，不阻塞 {@link #run}）。
     *
     * <p>各通道的归档彼此独立，无需保序；放这里可以并行，且不会把
     * 心跳/状态落库拖在身后。
     *
     * @param name 任务名（日志用）
     * @param task 任务体
     */
    public void runArchive(String name, Runnable task) {
        submit(archiveExecutor, name, task);
    }

    /** 统一提交：包装异常兜底与拒绝处理。 */
    private void submit(ExecutorService ex, String name, Runnable task) {
        try {
            ex.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("异步任务失败: {}", name, t);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：记录并丢弃。丢一条心跳比 OOM 好得多，
            // 下一次心跳（通常 5 分钟内）会自愈。
            long n = rejected.incrementAndGet();
            log.warn("异步队列已满，丢弃任务: {}（累计丢弃 {} 条）", name, n);
        }
    }

    /** 累计被丢弃的任务数（供监控/健康检查读取）。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /** 关闭执行器（容器销毁时调用）。 */
    public void shutdown() {
        shutdownOne(persistExecutor, "gb28181-persist");
        shutdownOne(archiveExecutor, "gb28181-archive");
    }

    private void shutdownOne(ExecutorService ex, String name) {
        ex.shutdown();
        try {
            if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("{} 未在 5 秒内结束，强制中断", name);
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 队列满时的拒绝策略：只记数不抛异常（抛了会打断 SIP 回调线程）。 */
    private static final class RejectLogger implements java.util.concurrent.RejectedExecutionHandler {
        private final String kind;

        RejectLogger(String kind) {
            this.kind = kind;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // 交给 submit() 的 catch 统一记日志，这里只负责抛出以触发它
            throw new RejectedExecutionException(kind + " 队列已满");
        }
    }
}
