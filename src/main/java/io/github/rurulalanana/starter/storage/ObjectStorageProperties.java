package io.github.rurulalanana.starter.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（对应 application.yml 的 {@code gb28181.oss.*}）。
 *
 * <p>采用 <b>S3 兼容协议</b>，一套配置覆盖主流对象存储：
 * <ul>
 *   <li>阿里云 OSS：{@code endpoint=https://oss-cn-hangzhou.aliyuncs.com}，
 *       region 任意（如 {@code cn-hangzhou}），{@code path-style=false}</li>
 *   <li>腾讯云 COS：{@code endpoint=https://cos.ap-guangzhou.myqcloud.com}，
 *       region 如 {@code ap-guangzhou}</li>
 *   <li>MinIO：{@code endpoint=http://192.168.1.10:9000}，
 *       {@code path-style=true}（必须）</li>
 *   <li>七牛云 Kodo：{@code endpoint=https://s3-cn-east-1.qiniucs.com}，
 *       region {@code cn-east-1}</li>
 *   <li>AWS S3：{@code endpoint} 留空自动推导，region 如 {@code us-east-1}</li>
 * </ul>
 *
 * <p>未配置 {@code access-key}/{@code secret-key} 或 {@code enabled=false} 时，
 * 自动退化为本地磁盘存储（录像文件原地保留，通过平台接口下载）。
 */
@Data
@ConfigurationProperties(prefix = "gb28181.oss")
public class ObjectStorageProperties {

    /**
     * 是否启用对象存储。
     *
     * <p>默认关闭。开启后云端录像在停止录制时自动上传，
     * 上传成功后可按配置删除本地文件。
     */
    private boolean enabled = false;

    /**
     * 服务端点（必填，除非用 AWS 官方 S3 让其自动推导）。
     *
     * <p>示例：{@code https://oss-cn-hangzhou.aliyuncs.com}。
     * 注意带上 {@code http(s)://} 前缀。
     */
    private String endpoint = "";

    /**
     * 区域标识。
     *
     * <p>阿里云 OSS / 腾讯云 COS 可填对应区域（如 {@code cn-hangzhou}），
     * 主要影响签名算法；MinIO 可填任意非空值（如 {@code us-east-1}）。
     */
    private String region = "us-east-1";

    /** 访问密钥 ID（必填）。 */
    private String accessKey = "";

    /** 访问密钥 Secret（必填）。 */
    private String secretKey = "";

    /** 存储桶名称（必填）。 */
    private String bucket = "";

    /**
     * 对象键前缀（目录），最终键为 {@code <prefix>/<通道编码>/<文件名>}。
     *
     * <p>默认 {@code record}，便于在桶内与其他业务数据隔离。
     */
    private String prefix = "record";

    /**
     * 是否使用 path-style 寻址。
     *
     * <p>{@code true} 时 URL 形如 {@code http://endpoint/bucket/key}；
     * {@code false}（默认）时形如 {@code http://bucket.endpoint/key}。
     * <b>MinIO、Ceph 等自建存储必须设为 {@code true}</b>；
     * 阿里云 OSS / 腾讯云 COS / AWS S3 支持虚拟主机风格，保持默认即可。
     */
    private boolean pathStyle = false;

    /**
     * 是否公共读。
     *
     * <p>{@code false}（默认）时 {@link ObjectStorageService#url} 生成带签名的
     * 临时地址（有效期见 {@link #signExpireSeconds}），适合私有桶；
     * {@code true} 时直接拼永久外链，适合已开启公共读的桶。
     */
    private boolean publicRead = false;

    /** 签名 URL 有效期（秒），默认 1 小时。 */
    private long signExpireSeconds = 3600;

    /**
     * 上传成功后是否删除本地录像文件。
     *
     * <p>默认 {@code false}（保留本地副本，便于回放兜底与排查）；
     * 磁盘紧张时可设为 {@code true}，上传成功后删除本地文件以节省空间。
     */
    private boolean deleteLocalAfterUpload = false;

    /**
     * 上传连接超时与读超时（毫秒）。
     *
     * <p>录像文件可能很大，读超时默认 5 分钟。
     */
    private long connectTimeoutMs = 10000;
    private long readTimeoutMs = 300000;

    /** 配置是否完整（启用且关键参数齐全）。 */
    public boolean usable() {
        return enabled
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()
                && bucket != null && !bucket.isBlank();
    }
}
