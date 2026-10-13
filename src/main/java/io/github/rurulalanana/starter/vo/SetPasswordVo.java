package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设置设备密码记录的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetPasswordVo {

    private String deviceId;

    /** 平台侧是否已留存密码。 */
    private Boolean hasPassword;

    /** 说明。 */
    private String message;
}
