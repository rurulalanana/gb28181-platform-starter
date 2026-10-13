package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 解码上墙应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecodeStartVo {

    private String streamId;
    private String deviceId;
    private String channelId;

    /** 状态：{@code PENDING} 表示指令已下发。 */
    private String state;

    /** 说明。 */
    private String note;
}
