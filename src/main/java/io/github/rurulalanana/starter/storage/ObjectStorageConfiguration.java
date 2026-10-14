package io.github.rurulalanana.starter.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。
 *
 * <p>按配置自动选择实现：
 * <ul>
 *   <li>{@code gb28181.oss.enabled=true} 且 access-key/secret-key/bucket 齐全
 *       → {@link S3ObjectStorage}（S3 兼容，覆盖主流对象存储）</li>
 *   <li>否则 → {@link LocalObjectStorage}（本地磁盘，录像原地保留）</li>
 * </ul>
 *
 * <p>配置不全时只告警不阻断启动，避免因存储配置失误导致整个平台起不来。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfiguration {

    @Bean
    public ObjectStorageService objectStorageService(ObjectStorageProperties props) {
        if (!props.isEnabled()) {
            log.info("对象存储未启用，云端录像保存在本地磁盘（gb28181.record-root）");
            return new LocalObjectStorage();
        }
        if (!props.usable()) {
            log.warn("gb28181.oss.enabled=true 但配置不完整（需 access-key/secret-key/bucket），"
                    + "已退化为本地磁盘存储");
            return new LocalObjectStorage();
        }
        try {
            return new S3ObjectStorage(props);
        } catch (Exception e) {
            log.error("对象存储初始化失败，已退化为本地磁盘存储：{}", e.getMessage());
            return new LocalObjectStorage();
        }
    }
}
