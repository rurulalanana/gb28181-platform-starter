package io.github.rurulalanana.starter.storage;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * S3 兼容对象存储实现。
 *
 * <p>用 AWS SDK v2 的 S3 客户端访问任意 S3 兼容存储，
 * 覆盖阿里云 OSS、腾讯云 COS、MinIO、七牛云 Kodo、Ceph RGW、AWS S3 等。
 *
 * <p>关键差异靠 {@link ObjectStorageProperties} 三个参数抹平：
 * <ul>
 *   <li>{@code endpoint} —— 各厂商服务地址</li>
 *   <li>{@code region} —— 签名区域（MinIO 填任意值）</li>
 *   <li>{@code pathStyle} —— MinIO/自建必须 {@code true}</li>
 * </ul>
 *
 * <p>本类由 {@code ObjectStorageConfiguration} 在配置可用时创建，
 * 不注册为组件（避免未配置时也实例化）。
 */
@Slf4j
public class S3ObjectStorage implements ObjectStorageService {

    private final ObjectStorageProperties props;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorage(ObjectStorageProperties props) {
        this.props = props;
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                props.getAccessKey(), props.getSecretKey());
        StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);

        var clientBuilder = S3Client.builder()
                .credentialsProvider(provider)
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyle())
                        .build());
        var presignerBuilder = S3Presigner.builder()
                .credentialsProvider(provider)
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyle())
                        .build());
        if (props.getEndpoint() != null && !props.getEndpoint().isBlank()) {
            clientBuilder.endpointOverride(URI.create(props.getEndpoint()));
            presignerBuilder.endpointOverride(URI.create(props.getEndpoint()));
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        log.info("对象存储已启用：endpoint={} bucket={} pathStyle={} publicRead={}",
                props.getEndpoint(), props.getBucket(), props.isPathStyle(), props.isPublicRead());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String type() {
        return TYPE_OSS;
    }

    @Override
    public UploadResult upload(Path file, String channelId) {
        if (file == null || !Files.exists(file)) {
            throw new StorageException("待上传文件不存在：" + file);
        }
        String key = buildKey(channelId, file.getFileName().toString());
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            throw new StorageException("读取文件大小失败：" + file, e);
        }
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType("video/mp4")
                            .build(),
                    RequestBody.fromFile(file));
            log.info("录像已上传对象存储：{} -> {}/{}（{} 字节）",
                    file, props.getBucket(), key, size);
            return new UploadResult(key, publicUrl(key), size);
        } catch (S3Exception e) {
            throw new StorageException("上传对象存储失败：" + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            throw new StorageException("上传对象存储异常：" + e.getMessage(), e);
        }
    }

    @Override
    public String url(String objectKey, Duration ttl) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (props.isPublicRead()) {
            return publicUrl(objectKey);
        }
        Duration expire = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofSeconds(props.getSignExpireSeconds()) : ttl;
        try {
            PresignedGetObjectRequest presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(expire)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(props.getBucket())
                                    .key(objectKey)
                                    .build())
                            .build());
            return presigned.url().toString();
        } catch (Exception e) {
            log.warn("生成签名 URL 失败: {} {}", objectKey, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return true;
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("删除对象存储文件失败: {} {}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("探测对象存储文件失败: {} {}", objectKey, e.getMessage());
            return false;
        }
    }

    // ==================== 内部 ====================

    /** 生成对象键：{@code <prefix>/<通道编码>/<文件名>}。 */
    private String buildKey(String channelId, String fileName) {
        String prefix = props.getPrefix() == null ? "" : props.getPrefix().trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String dir = channelId == null || channelId.isBlank() ? "unknown" : channelId;
        return prefix.isEmpty() ? dir + "/" + fileName : prefix + "/" + dir + "/" + fileName;
    }

    /** 拼接公共读外链。 */
    private String publicUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String endpoint = props.getEndpoint() == null ? "" : props.getEndpoint().trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (props.isPathStyle() || endpoint.isEmpty()) {
            // path-style：endpoint/bucket/key
            return endpoint + "/" + props.getBucket() + "/" + objectKey;
        }
        // virtual-hosted style：把 bucket 插到 host 前
        int schemeEnd = endpoint.indexOf("://");
        if (schemeEnd < 0) {
            return endpoint + "/" + props.getBucket() + "/" + objectKey;
        }
        String scheme = endpoint.substring(0, schemeEnd + 3);
        String host = endpoint.substring(schemeEnd + 3);
        return scheme + props.getBucket() + "." + host + "/" + objectKey;
    }
}
