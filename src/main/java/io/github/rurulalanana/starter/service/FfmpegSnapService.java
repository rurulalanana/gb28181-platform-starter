package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 服务端抓拍：拉 ZLM 的流 → ffmpeg 解码 → JPEG。
 *
 * <h2>为什么不用 JavaCV</h2>
 *
 * <p>本项目快照的图像源是 <b>H265</b>（实测级联下级推 HEVC），必须真正解码。
 * 各方案的体积代价：
 * <table border="1">
 *   <caption>方案对比</caption>
 *   <tr><th>方案</th><th>jar 增量</th><th>说明</th></tr>
 *   <tr><td><b>ffmpeg CLI（本方案）</b></td><td><b>0</b></td>
 *       <td>二进制放服务器磁盘，不进 jar</td></tr>
 *   <tr><td>JavaCV</td><td>~1.3MB + 27MB</td>
 *       <td>{@code javacv} 0.44MB + {@code javacpp} 0.54MB +
 *           {@code ffmpeg} Java 类 0.29MB 进 jar；但 native 库在
 *           {@code ffmpeg-linux-x86_64} 里，<b>26.9MB</b>，运行期还要联网下载</td></tr>
 *   <tr><td>JCodec</td><td>2.1MB</td><td>只支持 H264，对 H265 无效</td></tr>
 * </table>
 *
 * <p>用户要求「最小增加包容量」，JavaCV 光 native 就 27MB，出局；
 * JCodec 解不了 H265，也出局。只有 ffmpeg CLI 能做到 jar 增量 <b>0</b>。
 *
 * <h2>另一个关键事实：ZLM 自带的 native 不含 ffmpeg</h2>
 *
 * <p>{@code nm -a libmk_api.so | grep -c avcodec_} 结果为 <b>0</b> ——
 * 嵌入式 ZLMediaKit 编译时没带 ffmpeg，它自己解不了 H265，
 * 只负责「透传」编码数据（浏览器侧靠 jessibuca+wasm 解）。
 * 这与 ZLM 作者在 issues 中的说明一致：解码要自己集成 ffmpeg 或 javacv。
 *
 * <h2>为什么用「管道」而不是让 ffmpeg 直接拉 URL</h2>
 *
 * <p>实测：{@code ffmpeg -i http://...live.flv} 直接拉 <b>活流会挂死</b>
 * （活流永不结束，ffmpeg 一直等；加 {@code -rw_timeout} / {@code -timeout}
 * 也无效，实测 20~25 秒超时全部为空输出）。
 *
 * <p>而先把流读进管道再喂给 ffmpeg 的 stdin，ffmpeg 读到一帧就出图退出，
 * <b>实测耗时约 1 秒</b>（H264 与 H265 均验证通过）。
 *
 * <p>因此本类自己用 {@link HttpURLConnection} 拉 FLV，边读边写进 ffmpeg 的 stdin，
 * ffmpeg 输出 mjpeg 到 stdout —— 全程在内存中，不落临时文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfmpegSnapService {

    /** ffmpeg 可执行文件路径（外部进程，不进 jar）。 */
    private static final String FFMPEG = "/opt/gb28181/bin/ffmpeg";

    /** 拉流连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读流超时（毫秒）：活流一直不结束，靠这个上限兜底。 */
    private static final int READ_TIMEOUT_MS = 15_000;

    /** 单次抓拍的总墙钟上限（毫秒）。 */
    private static final long TOTAL_TIMEOUT_MS = 30_000;

    /** 喂给 ffmpeg 的最大字节数：够解出一帧即可（约 1~2MB）。 */
    private static final int MAX_FEED_BYTES = 4 * 1024 * 1024;

    private final Gb28181Properties props;

    /**
     * 抓取一帧 JPEG。
     *
     * @param flvUrl ZLM 的 FLV 播放地址
     * @return JPEG 字节；失败返回 {@code null}
     */
    public byte[] capture(String flvUrl) {
        if (flvUrl == null || flvUrl.isBlank()) {
            return null;
        }
        if (!Files.isExecutable(Path.of(FFMPEG))) {
            log.warn("ffmpeg 不可执行（{}），无法服务端抓拍", FFMPEG);
            return null;
        }

        Process proc = null;
        try {
            List<String> cmd = List.of(
                    FFMPEG,
                    "-hide_banner",
                    "-loglevel", "error",
                    // 容忍解码报错：刚开始拉流时可能丢到半个 GOP
                    "-err_detect", "ignore_err",
                    // 只输出关键帧。H265 的 P 帧依赖参考帧，
                    // 而 ZLM 刚建源时缓冲区里的帧可能不是从关键帧开始的 ——
                    // 直接解会报「Could not find ref with POC xx」并出一张坏图。
                    "-skip_frame", "nokey",
                    // 从 stdin 读 FLV 流；解出第一帧就结束
                    "-i", "pipe:0",
                    "-frames:v", "1",
                    "-f", "image2pipe",
                    "-vcodec", "mjpeg",
                    "-q:v", "3",
                    "pipe:1");
            Process p = new ProcessBuilder(cmd).start();
            proc = p;

            // ffmpeg 的 stdout 才是我们要的 JPEG，必须先起线程抽干，
            // 否则管道写满后 ffmpeg 阻塞、双方互等。
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(512 * 1024);
            Thread reader = new Thread(() -> copy(p.getInputStream(), jpeg, Integer.MAX_VALUE),
                    "ffmpeg-snap-out");
            reader.setDaemon(true);
            reader.start();

            // 边拉流边喂 ffmpeg；喂够就断开 stdin，让 ffmpeg 收尾退出。
            try (InputStream in = openStream(flvUrl)) {
                OutputStream stdin = p.getOutputStream();
                copy(in, stdin, MAX_FEED_BYTES);
                stdin.close();
            } catch (IOException e) {
                log.debug("拉流中断: url={} {}", flvUrl, e.getMessage());
            }

            p.waitFor(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            reader.join(3000);

            byte[] bytes = jpeg.toByteArray();
            if (bytes.length < 1024) {
                log.warn("抓拍失败：未取到有效 JPEG（{} 字节）", bytes.length);
                return null;
            }
            return bytes;
        } catch (Exception e) {
            log.warn("抓拍异常: url={} {}", flvUrl, e.getMessage());
            return null;
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /**
     * 打开 FLV 流。
     *
     * <p>只取 HTTP 头做校验，真正的读取在 {@link #capture} 里流式进行。
     */
    private InputStream openStream(String flvUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(flvUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "gb28181-snap/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("拉流失败 HTTP " + code);
        }
        return conn.getInputStream();
    }

    /**
     * 拷贝流。
     *
     * @param limit 最多拷贝的字节数；{@link Integer#MAX_VALUE} 表示不限
     */
    private void copy(InputStream in, OutputStream out, int limit) {
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        try {
            while (total < limit) {
                int want = (int) Math.min(buf.length, limit - total);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                out.flush();
                total += n;
            }
        } catch (IOException e) {
            // 读超时/连接断开属正常：已喂够数据时忽略
            log.debug("拷贝结束（已传 {} 字节）: {}", total, e.getMessage());
        }
    }

    /**
     * 构造本机 ZLM 的 FLV 地址。
     *
     * <p>单端口模式下 ZLM 的 app 固定为 {@code rtp}、stream 为 {@code <设备>_<通道>}；
     * 多端口模式 app 为设备编码、stream 为通道编码。
     * 这里不猜 —— 直接用会话里 ZLM 实际的 app/stream（{@link RtpSession}），
     * 两种模式都对。
     *
     * @param session 直播会话
     * @return 本机 FLV 地址；会话为空时返回 {@code null}
     */
    public String localFlvUrl(RtpSession session) {
        if (session == null) {
            return null;
        }
        return "http://127.0.0.1:" + props.getHttpPort()
                + "/" + session.getZlmApp() + "/" + session.getStream() + ".live.flv";
    }
}
