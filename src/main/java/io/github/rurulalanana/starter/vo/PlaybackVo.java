package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 回放流信息：在 {@link StreamVo} 基础上追加回放专属字段。
 *
 * <p>用 {@code org.springframework.beans.BeanUtils#copyProperties} 从 {@link StreamVo} 拷贝。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlaybackVo extends StreamVo {

    /** 回放起始时间（请求参数原样回显）。 */
    private String playbackStartTime;

    /** 回放结束时间（请求参数原样回显）。 */
    private String playbackEndTime;

    /** 回放倍速。 */
    private Integer playbackSpeed;

    /** 是否暂停。 */
    private Boolean playbackPaused;

    /** 是否下载模式。 */
    private Boolean download;

    /** 已回放秒数。 */
    private Long timestampSec;

    /** 回放进度（百分比）。 */
    private Integer playbackProgress;

    /** 下载进度（百分比）。 */
    private Integer downloadProgress;
}
