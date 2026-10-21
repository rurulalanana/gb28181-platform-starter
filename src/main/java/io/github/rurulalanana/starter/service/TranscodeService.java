package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h2>成本</h2>
 *
 * <p>实测 8.5 秒的 2560x1440 录像 → 720p H264 只需 <b>2 秒</b>（约 4 倍实时速度，
 * 8 核 CPU，ultrafast preset）。转好后缓存起来，第二次直接命中，秒开。
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

    private final Gb28181Properties props;

    /** 正在转码的记录 ID，避免同一段被并发转码。 */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * 取「浏览器可播」的录像文件路径；没有就转一道。
     *
     * @param recordId   录像 ID（用于缓存文件名）
     * @param sourcePath 原始录像文件路径
     * @return 转码后的文件路径；失败返回 {@code null}（调用方回退到原文件）
     */
    public String browserPlayable(String recordId, String sourcePath) {
        if (recordId == null || recordId.isBlank() || sourcePath == null) {
            return null;
        }
        Path src = Path.of(sourcePath);
        if (!Files.exists(src)) {
            return null;
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
        // 同一段只转一次
        if (inFlight.putIfAbsent(recordId, Boolean.TRUE) != null) {
            return Files.exists(out) ? out.toString() : null;
        }
        try {
            if (transcode(src, out)) {
                return out.toString();
            }
            return null;
        } finally {
            inFlight.remove(recordId);
        }
    }

    /**
     * 转码输出路径。
     *
     * @param recordId 录像 ID
     */
    private Path outPath(String recordId) {
        String base = props.getRecordRoot();
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.dir") + "/record";
        }
        return Path.of(base, SUBDIR, safe(recordId) + ".mp4");
    }

    /**
     * 执行转码：H265 → 720p H264 + faststart。
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
                "-i", src.toAbsolutePath().toString(),
                // 降到 720p：转码快 4 倍，且浏览器端够看
                "-vf", "scale=-2:720",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "28",
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
                return false;
            }
            if (proc.exitValue() != 0) {
                log.warn("录像转码失败（退出码 {}）: {}", proc.exitValue(), src.getFileName());
                return false;
            }
            if (!Files.exists(out) || Files.size(out) == 0) {
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

    /** 文件名安全化：只保留字母数字与下划线。 */
    private String safe(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
