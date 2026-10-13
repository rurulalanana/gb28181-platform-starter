package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像段（云端录像按日查询 / 区间播放）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudRecordVo {

    private String name;

    /** 开始时间（{@code yyyyMMddHHmmss}）。 */
    private String startAt;

    /** 结束时间（{@code yyyyMMddHHmmss}）。 */
    private String endTime;

    private Long fileSize;

    /** 录像时长（秒）。 */
    private Integer duration;

    /** 播放地址。 */
    private String hls;

    /** 是否重要录像。 */
    private Boolean important;

    /** 下载地址。 */
    private String downloadUrl;
}
