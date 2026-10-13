package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 指定时间段的录像段列表（{@code cloudrecord/video/...}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoRangeVo {

    /** 操作类型：{@code play} / {@code download}。 */
    private String operate;

    private String serial;
    private String code;

    /** 请求的起始时间。 */
    private String startTime;

    /** 请求的结束时间。 */
    private String endTime;

    /** 区间内的录像段数。 */
    private Integer recordCount;

    /** 区间内总字节数。 */
    private Long totalBytes;

    private List<CloudRecordVo> recordList;

    /** 说明。 */
    private String note;
}
