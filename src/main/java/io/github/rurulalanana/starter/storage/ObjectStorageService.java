package io.github.rurulalanana.starter.storage;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 对象存储服务抽象。
 *
 * <p>用于把云端录像（平台侧录制的 MP4）归档到对象存储。
 * 实现有两类：
 * <ul>
 *   <li>{@link LocalObjectStorage} —— 未配置对象存储时的本地磁盘实现，
 *       文件原地保留，{@link #url} 返回平台自身的下载接口。</li>
 *   <li>{@link S3ObjectStorage} —— S3 兼容协议实现，一套代码覆盖
 *       阿里云 OSS、腾讯云 COS、MinIO、七牛云、AWS S3、Ceph RGW 等主流对象存储。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：实现必须是线程安全的，上传可能由异步线程调用。
 */
public interface ObjectStorageService {

    /** 存储类型标识：本地磁盘。 */
    String TYPE_LOCAL = "LOCAL";

    /** 存储类型标识：对象存储。 */
    String TYPE_OSS = "OSS";

    /** 是否已启用对象存储（本地实现返回 {@code false}）。 */
    boolean enabled();

    /** 存储类型：{@link #TYPE_LOCAL} 或 {@link #TYPE_OSS}。 */
    String type();

    /**
     * 上传文件到对象存储。
     *
     * <p>上传成功后对象键由实现生成（默认 {@code record/<通道编码>/<文件名>}）。
     *
     * @param file      本地文件路径
     * @param channelId 通道编码（用于组织对象键目录）
     * @return 上传结果，含对象键与外链地址
     * @throws StorageException 上传失败
     */
    UploadResult upload(Path file, String channelId);

    /**
     * 生成可对外访问的 URL。
     *
     * <p>对象存储开启私有读时返回带签名的临时 URL；开启公共读时返回永久外链。
     * 本地实现返回 {@code null}（由调用方回退到平台下载接口）。
     *
     * @param objectKey 对象键
     * @param ttl       签名有效期（仅私有读模式有效，可为 {@code null} 用默认值）
     * @return 可访问 URL；无法生成时返回 {@code null}
     */
    String url(String objectKey, Duration ttl);

    /**
     * 删除对象。
     *
     * @param objectKey 对象键
     * @return 是否删除成功（对象不存在视为成功）
     */
    boolean delete(String objectKey);

    /** 对象是否存在。 */
    boolean exists(String objectKey);

    /** 上传结果。 */
    class UploadResult {

        private final String objectKey;
        private final String url;
        private final long size;

        public UploadResult(String objectKey, String url, long size) {
            this.objectKey = objectKey;
            this.url = url;
            this.size = size;
        }

        public String getObjectKey() {
            return objectKey;
        }

        /** 外链地址（公共读时为永久地址，私有读时可能为签名地址或 {@code null}）。 */
        public String getUrl() {
            return url;
        }

        public long getSize() {
            return size;
        }
    }

    /** 存储操作异常。 */
    class StorageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
