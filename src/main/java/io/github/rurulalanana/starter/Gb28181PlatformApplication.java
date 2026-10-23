package io.github.rurulalanana.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.rurulalanana.starter.config.Gb28181Properties;

/**
 * GB28181 流媒体平台测试工程。
 *
 * <p>启动后：
 * <ul>
 *   <li>SIP 信令服务（gb28181-sip）监听 5060/UDP，等设备注册</li>
 *   <li>媒体服务（gb28181-sip-zlm 内嵌 ZLMediaKit）监听 HTTP/RTMP/RTSP/WebRTC</li>
 *   <li>REST API 挂载在 {@code /api/v1/**}</li>
 *   <li>设备、通道、流、录像、报警等数据落 PostgreSQL（MyBatis-Plus）</li>
 *   <li>测试页面：<a href="http://localhost:8080/">localhost:8080</a></li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(Gb28181Properties.class)
public class Gb28181PlatformApplication {

    /**
     * 停机总超时（秒）：超过就 {@link Runtime#halt} 强制退出。
     *
     * <p><b>为什么必须有</b>：实测停机偶发卡死 —— Spring 的关闭流程
     * 11 秒内就跑完了（日志可见「GB28181 流媒体平台已停止」「HikariPool
     * Shutdown completed」），但 JVM 进程仍不退出，只能等 {@code gb.sh}
     * 的 60 秒超时后 {@code kill -9}。每次重启白等 1 分钟。
     *
     * <p>原因是还有非守护线程活着（原生库 / JNA / HTTP 客户端都可能），
     * 逐个定位并不可靠，而且这类线程往往不在我们的控制范围内。
     *
     * <p>取 30 秒：正常情况下关闭流程 12 秒内结束，留足 18 秒余量；
     * 真卡住时也能在 30 秒内退出而不是 60 秒。
     *
     * <p><b>halt 而不是 exit</b>：{@code System.exit} 会再次触发 shutdown hook
     * 并死锁；{@code halt} 直接终止 JVM，不做任何收尾 ——
     * 此时录像已经由 {@code Gb28181MediaPlatform#stop()} 停过并落盘，
     * 剩下的交给下次启动的录像对账。
     */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) {
        installShutdownWatchdog();
        SpringApplication.run(Gb28181PlatformApplication.class, args);
    }

    /**
     * 注册停机看门狗：进程收到 TERM 后若 {@value #SHUTDOWN_TIMEOUT_SECONDS} 秒
     * 仍未退出，强制 {@code halt(0)}。
     *
     * <p>它是<b>额外</b>的守护线程，与 Spring 自己的 shutdown hook 并行运行：
     * 后者正常结束时 JVM 直接退出，看门狗随之消失；只有后者卡住时看门狗才生效。
     *
     * <p>退出码用 0：这是「按预期收到了 TERM」的主动收尾，不该被
     * {@code gb.sh} 的守护循环当成崩溃处理（否则会触发退避重试）。
     */
    private static void installShutdownWatchdog() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(SHUTDOWN_TIMEOUT_SECONDS * 1000L);
                    // 能走到这里说明别的 shutdown hook 卡住了
                    System.err.println("[shutdown] 关闭超时 " + SHUTDOWN_TIMEOUT_SECONDS
                            + " 秒，强制退出（有非守护线程未结束）");
                    System.err.flush();
                    Runtime.getRuntime().halt(0);
                } catch (InterruptedException ignore) {
                    // 正常退出时 JVM 会在关闭过程中中断本线程，什么都不用做
                }
            }, "gb-shutdown-watchdog"));
        } catch (Throwable ignore) {
            // 注册失败不影响启动：最坏情况就是回到「等 gb.sh 的 60 秒超时」
        }
    }
}