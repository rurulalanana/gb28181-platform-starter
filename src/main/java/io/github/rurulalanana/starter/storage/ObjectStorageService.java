package io.github.rurulalanana.starter.storage;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 对象存储服务抽象：把云端录像归档到对象存储。{@link LocalObjectStorage} 是未配置时的本地磁盘实现
 * （文件原地保留，{@link #url} 返回平台下载接口）；{@link S3ObjectStorage} 用 S3 兼容协议一套代码覆盖
 * 阿里云 OSS、腾讯云 COS、MinIO、七牛、AWS S3、Ceph RGW。
 * 实现必须线程安全：上传可能由异步线程调用。
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
     * 上传文件。对象键由实现生成（默认 {@code record/<通道编码>/<文件名>}），返回对象键与外链地址；
     * 失败抛 {@link StorageException}。
     */
    UploadResult upload(Path file, String channelId);

    /**
     * 生成可对外访问的 URL：私有读返回带签名的临时 URL，公共读返回永久外链；
     * 本地实现返回 {@code null}，由调用方回退到平台下载接口。
     */
    String url(String objectKey, Duration ttl);

    /** 删除对象；对象不存在视为成功。 */
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

        /** 外链地址（公共读为永久地址；私有读可能为签名地址或 {@code null}）。 */
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
