package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 开始实时录像的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordStartVo {

    /** 录像 ID。 */
    private String recordId;

    private String channelId;

    /** 开始时间（{@code yyyyMMddHHmmss}）。 */
    private String startTime;

    /** 停止后可用的下载地址。 */
    private String downloadUrl;

    /** 说明。 */
    private String note;
}
