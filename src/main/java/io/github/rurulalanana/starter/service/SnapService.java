package io.github.rurulalanana.starter.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 通道快照的存储与读取（存图、取图、清过期）。
 *
 * <p>快照只能由服务端产生：现场推 H265，必须真解码才能出 JPEG；浏览器抓帧依赖 wasm 解码器、
 * 跨域 canvas 读像素受限、页面一关就没了，已全部移除。现在由 {@link FfmpegSnapService}
 * 用外部 ffmpeg 拉 ZLM 的流解码（不引 JavaCV，jar 增量为 0），编排见 {@link SnapCaptureService}。
 * 不能用 ZLM 的 Web API：ZLM4J 1.12.0 内嵌的 ZLMediaKit 不含 Web API（{@code nm -D} 查不到
 * {@code mk_web_api}），{@code /index/api/getSnap} 不可用。
 *
 * <p>存储布局 {@code <recordRoot>/snap/<serial>_<code>.jpg}，与录像同根便于整体备份 / 清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapService {

    /** 快照子目录名（放在录像根目录下，与录像同根便于备份）。 */
    private static final String SUBDIR = "snap";

    /** 快照最长保留时间（毫秒），默认 1 小时。 */
    private static final long MAX_AGE_MS = 60 * 60 * 1000L;

    private final Gb28181Properties props;

    /** 快照根目录。 */
    private Path root() {
        return Path.of(props.resolvedRecordRoot(), SUBDIR);
    }

    /**
     * 保存服务端抓拍产生的 JPEG 字节（ffmpeg 已在内存里产出，无需再 Base64）。
     *
     * @return 快照的访问路径（相对平台 HTTP 根）；失败返回 {@code null}
     */
    public String saveBytes(String serial, String code, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Path dir = root();
            Files.createDirectories(dir);
            // 文件名只允许安全字符：编码是 20 位数字，天然安全，但仍做一次过滤
            Path file = dir.resolve(safe(serial) + "_" + safe(code) + ".jpg");
            // 先写临时文件再原子替换，避免前端同时请求时读到半张图
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return "/api/v1/cloudrecord/snap/" + safe(serial) + "/" + safe(code) + ".jpg";
        } catch (IOException e) {
            log.warn("保存快照失败: serial={} code={} {}", serial, code, e.getMessage());
            return null;
        }
    }

    /**
     * 读取快照。
     *
     * @return 图像字节；不存在时返回 {@code null}
     */
    public byte[] read(String serial, String code) {
        try {
            // 先清理再读：若放在检查之后，会出现「刚确认存在就被自己删了」的 NoSuchFileException 竞态
            purgeExpired();
            Path file = root().resolve(safe(serial) + "_" + safe(code) + ".jpg");
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.debug("读取快照失败: serial={} code={} {}", serial, code, e.getMessage());
            return null;
        }
    }

    /**
     * 快照是否可用。只探测存在性（读几十 KB 是白浪费 IO）。
     * 必须把「过期」也算作不存在：清理是延迟的，只判断 {@code Files.exists} 会让列表显示有缩略图
     * 而点开 404，或者把几分钟前的旧画面当成当前状态。
     *
     * @return 是否有一张未过期的快照
     */
    public boolean exists(String serial, String code) {
        try {
            Path file = root().resolve(safe(serial) + "_" + safe(code) + ".jpg");
            if (!Files.exists(file)) {
                return false;
            }
            return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
                    <= MAX_AGE_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清理过期快照（默认 1 小时）：快照是「访问列表时现抓」的产物，过期图会让人
     * 误以为通道当前状态如此。
     */
    public void purgeExpired() {
        try {
            Path dir = root();
            if (!Files.isDirectory(dir)) {
                return;
            }
            long now = System.currentTimeMillis();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jpg")).forEach(p -> {
                    try {
                        if (now - Files.getLastModifiedTime(p).toMillis() > MAX_AGE_MS) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignore) {
                        // 单个文件清理失败不影响其它
                    }
                });
            }
        } catch (IOException e) {
            log.debug("清理过期快照失败: {}", e.getMessage());
        }
    }

    /** 文件名安全化：只保留字母数字与下划线，避免路径穿越。 */
    private String safe(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
