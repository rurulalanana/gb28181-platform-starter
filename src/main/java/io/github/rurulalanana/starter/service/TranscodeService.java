package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.Mp4Probe;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 录像的「浏览器可播」转码服务：H265 → H264（720p + faststart）。
 *
 * <h2>为什么必须转码</h2>
 *
 * <p>现场推的是 <b>H265</b>，而浏览器（实测 Chrome/Electron 148）
 * <b>实际解不了</b> HEVC ——
 * {@code canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"')} 虽然返回 {@code probably}，
 * 但真正播放时 FFmpegDemuxer 报 {@code PIPELINE_ERROR_DECODE: video decode error}。
 * （Chrome for Linux 不带 HEVC 专有解码器；jessibuca 能播是因为它用 wasm 软解码。）
 *
 * <p>但 <b>H264 可以</b>：实测 720p H264 MP4 播放正常、时间正常推进。
 * 所以服务端转一道 H265 → H264，前端就能用<b>原生 {@code <video>}</b> 播 ——
 * 而原生 video 支持 HTTP Range，<b>拖动进度条直接定位</b>，不需要自己做 seek。
 *
 * <h2>为什么还要求 faststart</h2>
 *
 * <p>ZLM 录出来的 MP4 是「mdat 在前、moov 在尾」的非 faststart 布局。
 * 浏览器播这样的文件时需要先读文件尾找 moov，再回头读 mdat，容易出问题。
 * 转码时加 {@code -movflags +faststart} 把 moov 挪到文件头，顺带解决了这个。
 *
 * <p><b>绝大多数录像根本不用转码</b>：ZLM 录出来的 MP4 <b>本来就是 H264</b>
 * （实测 1280x720/15fps），浏览器能直接播。实测一条 1 小时的录像
 * 源文件 357MB，无谓地转一道后变成 <b>690MB</b> —— 体积翻倍、CPU 白烧，
 * 浏览器拉的还是个「大文件」，当然卡。
 *
 * <p>所以这里的策略是：<b>先探测编码，是 H264 就原文件直接给出去</b>
 * （见 {@link Mp4Probe}）；只有 H265 才转码。
 *
 * <h2>为什么 H265 必须转码</h2>
 *
 * <p>实测 Chrome：{@code canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"')}
 * 返回 {@code probably}，但真正播放时报
 * {@code PIPELINE_ERROR_DECODE}（error code 3）—— 骗人的。
 * Chrome for Linux 不带 HEVC 专有解码器，必须转成 H264。
 *
 * <h2>成本</h2>
 *
 * <p>实测 5 秒的 2560x1440 H265 录像 → 720p H264 只需 <b>1.8 秒</b>。
 * 转好后缓存起来，第二次直接命中，秒开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    /** ffmpeg 可执行文件（外部进程，不进 jar）。 */
    private static final String FFMPEG = "/opt/gb28181/bin/ffmpeg";

    /** 转码输出目录名（放在录像根目录下，与录像同根便于清理）。 */
    private static final String SUBDIR = "h264";

    /** 转码超时（毫秒）。 */
    private static final long TIMEOUT_MS = 120_000;

    /**
     * 请求侧等待转码完成的上限（毫秒）。
     *
     * <p>超过就告诉用户「转码中，稍后重试」——转码本身继续在后台跑，
     * 下次请求直接命中缓存。
     */
    private static final long WAIT_MS = 30_000;

    /** 输出分辨率高度（宽度按 16:9 自动推算）。 */
    private static final int HEIGHT = 720;

    /**
     * 缓存版本号。
     *
     * <p>转码参数一变，旧缓存就是废的（而且可能是 690MB 那种坏文件），
     * 靠版本号让旧缓存自动失效，不留垃圾也不用手动清。
     */
    private static final String CACHE_VERSION = "v2";

    private final Gb28181Properties props;

    /**
     * 正在进行的转码任务。
     *
     * <p>用 {@link CompletableFuture} 而不是布尔标记：并发请求同一段录像时，
     * 后来者<b>等待</b>同一个结果，而不是拿不到文件就回退到原始 H265
     * （那样浏览器根本解不了，表现为「播不出来」）。
     */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight =
            new ConcurrentHashMap<>();

    /**
     * 转码线程池。
     *
     * <p>单独 2 个线程：转码是 CPU 密集的，给多了反而抢业务线程的 CPU；
     * 录像一般不会同时有几十段要转。
     */
    private final ExecutorService transcodePool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gb-record-transcode");
        t.setDaemon(true);
        return t;
    });


    /**
     * 取「浏览器可播」的录像文件路径。
     *
     * <p><b>已经是 H264 的直接返回原文件，不转码。</b>
     * 实测 ZLM 录出来的文件<b>本来就是 H264</b>（720p/15fps），
     * 无谓地转一道反而把 357MB 撑成 690MB —— 体积翻倍、CPU 白烧，
     * 浏览器拉的还是个「大文件」。
     *
     * <p>只有 <b>H265</b> 才需要转码：Chrome for Linux 不带 HEVC 专有解码器，
     * 实测 {@code canPlayType} 虽返回 {@code probably}，真正播放时报
     * {@code PIPELINE_ERROR_DECODE}。
     *
     * @param recordId   录像 ID（用于缓存文件名）
     * @param sourcePath 原始录像文件路径
     * @return 可播的文件路径；失败返回 {@code null}（调用方回退到原文件）
     */
    public String browserPlayable(String recordId, String sourcePath) {
        if (recordId == null || recordId.isBlank() || sourcePath == null) {
            return null;
        }
        Path src = Path.of(sourcePath);
        if (!Files.exists(src)) {
            return null;
        }
        // 已经是浏览器能解的编码 → 原文件直接给出去，零开销
        if (Mp4Probe.browserPlayable(src)) {
            return src.toString();
        }
        Path out = outPath(recordId);
        if (Files.exists(out)) {
            try {
                if (Files.size(out) > 0) {
                    return out.toString();
                }
            } catch (Exception e) {
                log.debug("读取转码缓存大小失败: {}", e.getMessage());
            }
        }
        /*
         * 同一段只转一次，后来者等同一个结果。
         *
         * <p>早先这里用布尔标记：并发请求时后来者直接返回 null，
         * 调用方回退到原始 H265 文件 —— 浏览器解不了，表现为「播不出来」。
         * 现在用 CompletableFuture，后来者 await 同一个任务的结果。
         *
         * <p><b>转码跑在后台线程池</b>：一段 1 小时的 H265 录像转码要好几分钟，
         * 放在请求线程里会把 Tomcat 线程占死（还可能超时）。
         * 放到后台后，请求最多等 {@link #WAIT_MS}，等不到就告诉用户「转码中，稍后重试」，
         * 转码本身继续跑，下次请求直接命中缓存。
         */
        CompletableFuture<String> task = CompletableFuture.supplyAsync(() -> {
            boolean ok = transcode(src, out);
            if (!ok) {
                return null;
            }
            // 参数改版后旧缓存不再可用，顺手清掉（早先那版单个可达 690MB）
            cleanOldCache(recordId, out);
            return out.toString();
        }, transcodePool);
        CompletableFuture<String> running = inFlight.putIfAbsent(recordId, task);
        if (running != null) {
            try {
                return running.get(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.info("录像 {} 转码尚未完成，稍后重试", recordId);
                return null;
            } catch (Exception e) {
                log.debug("等待转码结果失败: {}", e.getMessage());
                return null;
            }
        }
        try {
            String result = task.get(WAIT_MS, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            log.info("录像 {} 转码尚未完成，稍后重试", recordId);
            return null;
        } catch (Exception e) {
            log.debug("等待转码结果失败: {}", e.getMessage());
            return null;
        } finally {
            inFlight.remove(recordId, task);
        }
    }

    /**
     * 删除同一段录像的旧版缓存。
     *
     * <p>转码参数改过之后，旧缓存既占盘又不能用 ——
     * 早先未重置时间戳的版本单个能到 690MB，攒几段就把盘塞满了。
     *
     * @param recordId 录像 ID
     * @param keep     保留的新缓存文件
     */
    private void cleanOldCache(String recordId, Path keep) {
        try {
            Path dir = keep.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            // 旧缓存命名是 <recordId>.mp4，新的是 <版本>-<recordId>.mp4
            String suffix = ".mp4";
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(suffix))
                        .filter(f -> !f.equals(keep))
                        .filter(f -> f.getFileName().toString().endsWith(safe(recordId) + suffix))
                        .forEach(f -> {
                            try {
                                Files.deleteIfExists(f);
                                log.info("清理旧转码缓存: {}", f.getFileName());
                            } catch (Exception e) {
                                log.debug("删除旧转码缓存失败: {}", e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.debug("清理旧转码缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 转码输出路径。
     *
     * <p>文件名带版本号（{@link #CACHE_VERSION}），参数一改旧缓存自动失效。
     */
    private Path outPath(String recordId) {
        String base = props.getRecordRoot();
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.dir") + "/record";
        }
        return Path.of(base, SUBDIR, safe(CACHE_VERSION + "-" + recordId) + ".mp4");
    }

    /**
     * 执行转码：H265 → 720p H264 + faststart，<b>并重置时间戳</b>。
     *
     * <p>参数说明：
     * <ul>
     *   <li>{@code -fflags +genpts}：源文件时间戳不可靠时重新生成；</li>
     *   <li>{@code fps}（<b>不带参数</b>）：跟随源的帧率输出。
     *       源是 15fps 就出 15fps —— 写死 25fps 会把 15fps 的源补成 25fps，
     *       凭空多出 60% 的重复帧，文件白白变大；</li>
     *   <li>{@code setpts=N/FRAME_RATE/TB}：按帧序重置时间戳，
     *       避免源里异常的时间戳跨度被 CFR 化时补出大量重复帧；</li>
     *   <li>{@code asetpts=N/SR/TB}：音频同样重置，否则音画会错位；</li>
     *   <li>{@code -g 50}：关键帧间隔 2 秒，拖进度条能快速起播；</li>
     *   <li>{@code -preset veryfast}：比 ultrafast 画质好很多，耗时仍远小于实时；</li>
     *   <li>{@code -movflags +faststart}：moov 挪到文件头，浏览器能边下边播。</li>
     * </ul>
     *
     * @param src 原文件
     * @param out 输出文件
     * @return 是否成功
     */
    private boolean transcode(Path src, Path out) {
        if (!new File(FFMPEG).canExecute()) {
            log.warn("ffmpeg 不可执行（{}），跳过转码", FFMPEG);
            return false;
        }
        try {
            Files.createDirectories(out.getParent());
        } catch (Exception e) {
            log.warn("创建转码目录失败: {}", e.getMessage());
            return false;
        }
        List<String> cmd = List.of(
                FFMPEG,
                "-hide_banner",
                "-loglevel", "error",
                // 源时间戳不可靠，重新生成
                "-fflags", "+genpts",
                "-i", src.toAbsolutePath().toString(),
                // 降到 720p；fps 不带参数 = 跟随源帧率（源 15fps 就出 15fps，
                // 写死 25fps 会凭空多出 60% 的重复帧，文件白白变大）；
                // setpts 按帧序重置时间戳
                "-vf", "scale=-2:" + HEIGHT + ",fps,setpts=N/FRAME_RATE/TB",
                // 音频同步重置
                "-af", "asetpts=N/SR/TB",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "26",
                // 关键帧间隔 2 秒：拖进度条能快速出画面
                "-g", "50",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                // moov 挪到文件头，浏览器能顺畅播
                "-movflags", "+faststart",
                "-y", out.toAbsolutePath().toString());
        Process proc = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 读干 stdout（避免管道写满阻塞）
            byte[] buf = new byte[4096];
            while (proc.getInputStream().read(buf) >= 0) {
                // 丢弃
            }
            boolean done = proc.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                log.warn("录像转码超时: {}", src.getFileName());
                deleteQuietly(out);
                return false;
            }
            if (proc.exitValue() != 0) {
                log.warn("录像转码失败（退出码 {}）: {}", proc.exitValue(), src.getFileName());
                deleteQuietly(out);
                return false;
            }
            if (!Files.exists(out) || Files.size(out) == 0) {
                deleteQuietly(out);
                return false;
            }
            log.info("录像转码完成: {} -> {} ({} 字节)", src.getFileName(), out.getFileName(),
                    Files.size(out));
            return true;
        } catch (Exception e) {
            log.warn("录像转码异常: {} {}", src.getFileName(), e.getMessage());
            return false;
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /** 删除文件，失败只记日志（清理缓存失败不该影响主流程）。 */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.debug("删除文件失败: {}", e.getMessage());
        }
    }

    /** 文件名安全化：只保留字母数字与下划线。 */
    private String safe(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
