package io.github.rurulalanana.starter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 平台异步任务执行器。
 *
 * <p>SIP 回调运行在协议栈线程上，<b>禁止阻塞</b>（阻塞会导致 SIP 重传与超时）；
 * 但落库是 I/O 操作。因此把「回调 → 落库」这一段放到单线程队列里串行执行：
 * <ul>
 *   <li>回调线程只做入队，立即返回</li>
 *   <li>单线程保证同一设备的注册 / 心跳 / 目录按顺序落库，避免状态竞争</li>
 * </ul>
 */
@Slf4j
@Component
public class PlatformAsyncExecutor {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gb28181-persist");
        t.setDaemon(true);
        return t;
    });

    /**
     * 提交一个异步任务；任务内部异常只记录日志，不影响调用方。
     *
     * @param name 任务名（日志用）
     * @param task 任务体
     */
    public void run(String name, Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("异步任务失败: {}", name, t);
            }
        });
    }

    /** 关闭执行器（容器销毁时调用）。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
