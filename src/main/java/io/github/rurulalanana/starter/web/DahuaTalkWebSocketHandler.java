package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.service.DahuaTalkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 大华双工对讲的<b>音频下行</b>通道：设备 → 服务端 → 浏览器。
 *
 * <p>地址：{@code ws(s)://<host>/api/v1/dahua/talk/audio?talkId=xxx&token=xxx}，
 * 服务端只往下发<b>二进制帧</b>（裸 PCM：8 kHz / 16 bit / 单声道，帧长 ≈ 130 ms）。
 *
 * <h3>为什么鉴权走 query 里的 token</h3>
 * 浏览器 {@code new WebSocket()} <b>不能带自定义请求头</b>，而平台的登录态在
 * {@code Authorization} 头里。只能把 token 放 query。代价是 token 会进 nginx
 * access log —— 因此这里<b>不</b>信任"MVC 拦截器已经放行"这个事实，
 * 而是在 {@link #afterConnectionEstablished} 里自己用 {@code StpUtil.getLoginIdByToken}
 * 再校验一次，校验不过直接 {@code close(1008)}。
 *
 * <p>对应地，{@code SaTokenConfiguration} 把该路径加进了 {@code excludePathPatterns}：
 * 握手请求走的是普通 MVC 拦截器，不放行就根本进不到这里
 * （和 {@code /dahua/snapupload} 同一思路）。
 *
 * <h3>为什么发送要丢到线程池</h3>
 * 音频来自 JNA 的<b>原生回调线程</b>（SDK 的音频泵）。在那条线程里直接
 * {@code sendMessage} 一旦遇到网络卡顿就会把整台设备的对讲音频泵堵住，
 * 影响的是同一个 SDK 进程里的其它功能。所以这里投递到有界队列由独立线程发送，
 * 队列满时<b>直接丢弃</b> —— 实时音频宁可丢帧也不要拖住采集端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DahuaTalkWebSocketHandler extends BinaryWebSocketHandler {

    /** 单个会话未发出的帧上限：约 128 帧 ≈ 16 秒音频，超过说明客户端已经跟不上。 */
    private static final int SEND_QUEUE_SIZE = 128;

    private final DahuaTalkService talkService;

    /** sessionId → 取消订阅句柄。 */
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    /**
     * 发送线程池。
     *
     * <p>两个线程足够：单路对讲每秒只有 7~8 帧，每个会话内部还会
     * {@code synchronized} 串行化；用池而不是单线程，是为了避免一个卡住的
     * 客户端把同值班室其它接收端一起拖住。
     */
    private final ExecutorService senders = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(SEND_QUEUE_SIZE * 8),
            new SendThreadFactory(),
            new ThreadPoolExecutor.DiscardPolicy());

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String talkId = queryParam(session, "talkId");
        String token = queryParam(session, "token");

        // 1) 登录态：token 缺失/无效一律拒绝，不放行任何匿名收听
        Object loginId = null;
        if (token != null && !token.isBlank()) {
            try {
                loginId = StpUtil.getLoginIdByToken(token);
            } catch (Exception e) {
                log.debug("对讲 WebSocket 校验 token 异常: {}", e.toString());
            }
        }
        if (loginId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION,
                    "登录已失效，请重新登录后再收听");
            return;
        }
        // 2) 会话存在性：对讲可能刚被停止/超时释放
        if (talkId == null || talkId.isBlank() || !talkService.exists(talkId)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION, "对讲会话不存在或已结束");
            return;
        }

        try {
            AutoCloseable handle = talkService.subscribe(talkId, pcm -> dispatch(session, pcm));
            subscriptions.put(session.getId(), handle);
            log.info("对讲收听已连接: talkId={} user={} session={}", talkId, loginId, session.getId());
        } catch (Exception e) {
            log.warn("订阅对讲音频失败: talkId={} err={}", talkId, e.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR, "订阅失败：" + e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 上行音频走 HTTP（/talk/send）：那里有现成的鉴权、限流与错误回显，
        // 也避免 WebSocket 双向流量互相干扰。这里收到数据只当客户端写错了地方。
        log.debug("忽略对讲 WebSocket 的上行数据: session={} bytes={}",
                session.getId(), message.getPayloadLength());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AutoCloseable handle = subscriptions.remove(session.getId());
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ignored) {
                // 取消订阅失败不影响连接关闭
            }
        }
        log.info("对讲收听已断开: session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("对讲 WebSocket 传输异常: session={} {}", session.getId(), exception.toString());
    }

    /** 把一帧 PCM 投递给某个会话（异步，队列满则丢弃）。 */
    private void dispatch(WebSocketSession session, byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        try {
            senders.execute(() -> sendNow(session, pcm));
        } catch (Exception e) {
            // 池已关闭（应用退出中）：丢弃即可
            log.trace("对讲音频投递被拒绝: {}", e.toString());
        }
    }

    private void sendNow(WebSocketSession session, byte[] pcm) {
        if (!session.isOpen()) {
            return;
        }
        try {
            // WebSocketSession 不允许并发发送：同一会话内必须串行
            synchronized (session) {
                session.sendMessage(new BinaryMessage(pcm));
            }
        } catch (Exception e) {
            log.debug("对讲音频下发失败（丢弃该帧）: session={} {}", session.getId(), e.toString());
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst(name);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status, String reason) {
        try {
            if (session.isOpen()) {
                session.close(status.withReason(reason));
            }
        } catch (Exception ignored) {
            // 关闭失败无所谓，连接本来就要断
        }
    }

    /** 发送线程用 daemon：不能拦住 JVM 退出。 */
    private static final class SendThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dahua-talk-ws-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
