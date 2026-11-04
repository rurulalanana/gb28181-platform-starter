package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 云台扩展动作结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DahuaPtzActionResultVo {

    private String action;

    private String channelId;

    private Boolean ok;

    /**
     * 操作提示。
     *
     * <p>云台动作是异步的（设备收到就返回，真正转到位要几秒），
     * 所以这里给的是「下一步该看什么」，而不是「已经完成」。
     */
    private String note;
}
