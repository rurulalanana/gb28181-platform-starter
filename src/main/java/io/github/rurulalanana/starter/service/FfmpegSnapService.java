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
import java.util.concurrent.TimeUnit;

/**
 * 服务端抓拍：拉 ZLM 的 FLV 流 → ffmpeg 解码 → JPEG。
 *
 * <p>不用 JavaCV：快照源是 H265，JavaCV 光 native（{@code ffmpeg-linux-x86_64}）就 26.9MB 且运行期还要联网下载；
 * JCodec 只支持 H264。ffmpeg CLI 放在服务器磁盘，jar 增量为 0。
 * ZLM 自带的 native 不含 ffmpeg（{@code nm -a libmk_api.so | grep -c avcodec_} 为 0），它只透传编码数据。
 *
 * <p>必须用管道而不是让 ffmpeg 直接拉 URL：实测 {@code ffmpeg -i http://...live.flv} 在活流上会挂死
 * （加 {@code -rw_timeout} / {@code -timeout} 也无效，20~25 秒全部空输出）；
 * 边读 FLV 边写 stdin，ffmpeg 读到一帧即出图退出，实测约 1 秒（H264 / H265 均验证）。
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

    /** 抓取一帧 JPEG（要求关键帧）。
     *
     * @return JPEG 字节；失败返回 {@code null}
     */
    public byte[] capture(String flvUrl) {
        return capture(flvUrl, true);
    }

    /**
     * 抓取一帧 JPEG。
     *
     * @param requireKeyFrame {@code true} 只取关键帧（画面完整，但流刚建立时可能几秒内没有关键帧、得到 0 字节）；
     *                        {@code false} 不限制帧类型（一定能出图，但起点是 P 帧会花屏）
     * @return JPEG 字节；失败返回 {@code null}
     */
    public byte[] capture(String flvUrl, boolean requireKeyFrame) {
        if (flvUrl == null || flvUrl.isBlank()) {
            return null;
        }
        if (!Files.isExecutable(Path.of(FFMPEG))) {
            log.warn("ffmpeg 不可执行（{}），无法服务端抓拍", FFMPEG);
            return null;
        }

        Process proc = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                    FFMPEG,
                    "-hide_banner",
                    "-loglevel", "error",
                    // 容忍解码报错：刚开始拉流时可能丢到半个 GOP
                    "-err_detect", "ignore_err"));
            if (requireKeyFrame) {
                // 只输出关键帧：ZLM 刚建源时缓冲区的帧可能不从关键帧开始，直接解会报 POC 错误并出坏图
                cmd.add("-skip_frame");
                cmd.add("nokey");
            }
            cmd.addAll(java.util.List.of(
                    // 从 stdin 读 FLV 流；解出第一帧就结束
                    "-i", "pipe:0",
                    "-frames:v", "1",
                    "-f", "image2pipe",
                    "-vcodec", "mjpeg",
                    "-q:v", "3",
                    "pipe:1"));
            Process p = new ProcessBuilder(cmd).start();
            proc = p;

            // ffmpeg 的 stdout 是我们要的 JPEG，必须先起线程抽干，否则管道写满后双方互等
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
                // 降级为 debug：上层会重试并给出用户可见错误，这里再打 WARN 只是同一件事刷两遍
                log.debug("抓拍未取到有效 JPEG（{} 字节, requireKeyFrame={}）",
                        bytes.length, requireKeyFrame);
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

    /** 打开 FLV 流：只做连接与状态码校验，读取在 {@link #capture} 里流式进行。 */
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

    /** 拷贝流。
     *
     * @param limit 最多拷贝字节数；{@link Integer#MAX_VALUE} 表示不限
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
     * 构造本机 ZLM 的 FLV 地址。直接用会话里实际的 app/stream，不猜模式
     * （单端口是 {@code rtp/<设备>_<通道>}，多端口是设备编码 / 通道编码）。
     *
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
