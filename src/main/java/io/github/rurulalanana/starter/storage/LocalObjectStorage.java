package io.github.rurulalanana.starter.storage;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地磁盘存储实现（未配置对象存储时的兜底）。
 *
 * <p>不做任何搬运：录像文件由 ZLMediaKit 直接写在 {@code gb28181.record-root}，
 * 本实现只标记类型为 {@link #TYPE_LOCAL}，{@link #url} 返回 {@code null}
 * 由调用方回退到平台下载接口（{@code /api/v1/cloudrecord/file/{recordId}}）。
 *
 * <p>由 {@code ObjectStorageConfiguration} 决定是否装配，故不加 {@code @Component}。
 */
@Slf4j
public class LocalObjectStorage implements ObjectStorageService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String type() {
        return TYPE_LOCAL;
    }

    @Override
    public UploadResult upload(Path file, String channelId) {
        // 本地存储无需上传：对象键用文件绝对路径表示，便于下载接口直接定位
        long size = 0;
        try {
            if (file != null && Files.exists(file)) {
                size = Files.size(file);
            }
        } catch (Exception e) {
            log.warn("读取本地录像大小失败: {} {}", file, e.getMessage());
        }
        return new UploadResult(file == null ? null : file.toAbsolutePath().toString(), null, size);
    }

    @Override
    public String url(String objectKey, Duration ttl) {
        return null;
    }

    @Override
    public boolean delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return true;
        }
        try {
            return Files.deleteIfExists(Path.of(objectKey));
        } catch (Exception e) {
            log.warn("删除本地录像文件失败: {} {}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return objectKey != null && !objectKey.isBlank() && Files.exists(Path.of(objectKey));
    }
}
