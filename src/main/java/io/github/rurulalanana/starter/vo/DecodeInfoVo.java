package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 解码器状态。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecodeInfoVo {

    private String deviceId;
    private String channelId;
    private String channelName;

    /** 通道状态。 */
    private String status;

    /** 当前流 ID。 */
    private String streamId;

    /** 输出路数。 */
    private Integer numOutputs;

    /** 说明。 */
    private String note;
}
