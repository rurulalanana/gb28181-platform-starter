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
 * 平台异步任务执行器：SIP 回调运行在协议栈线程上禁止阻塞，而落库是 I/O，故放到队列里异步执行。
 *
 * <p>两条独立队列：{@link #run} 轻量落库（心跳 / 状态 / 目录），单线程保序；
 * {@link #runArchive} 录像归档，可并行且耗时长。不能混用：归档内部会轮询等 ZLM 写终态（最多 3 秒），
 * 10 路同时掉线就是 30 秒，共用队列会把所有心跳落库堵住（表现为「设备离线了界面还在线」）。
 *
 * <p>两条队列都有界：无界队列在下游阻塞时会膨胀到 OOM，有界 + 拒绝至少能保住进程并留下日志。
 */
@Slf4j
@Component
public class PlatformAsyncExecutor {

    /**
     * 保序队列容量：正常水位几十条，2000 是为了容忍重启后几百台设备集中注册的瞬时涌入。
     * 真打满说明下游已堵死，此时丢弃比 OOM 好。
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
     * 归档队列（2 线程）：主要耗时是等 ZLM 写终态（最多 3 秒）+ 一次 INSERT，
     * 2 个线程够覆盖多路同时掉线，又不至于并发事务过多。
     */
    private final ExecutorService archiveExecutor =
            new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(ARCHIVE_QUEUE_CAPACITY),
                    r -> daemonThread(r, "gb28181-archive"),
                    new RejectLogger("归档"));

    /** 因队列满被丢弃的任务数（可观测性：持续增长说明下游堵了）。 */
    private final AtomicLong rejected = new AtomicLong();

    /**
     * 提交一个保序异步任务（单线程 FIFO，同一设备的注册 / 心跳 / 目录按提交顺序执行）；
     * 任务内部异常只记日志，不影响调用方。
     */
    public void run(String name, Runnable task) {
        submit(persistExecutor, name, task);
    }

    /**
     * 提交一个录像归档任务（独立队列）：各通道归档彼此独立、无需保序，
     * 并行执行且不会把心跳 / 状态落库拖在身后。
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
