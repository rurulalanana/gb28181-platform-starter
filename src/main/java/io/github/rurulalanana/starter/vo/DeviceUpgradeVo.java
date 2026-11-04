package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设备升级应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceUpgradeVo {

    /**
     * 升级结果：{@code OK} / {@code ERROR} 是设备的控制应答（受不受理），
     * {@code PENDING} 表示设备没回应答、只能等 {@code DeviceUpgradeResult} 通知。
     */
    private String upgradeResult;

    /** 固件版本。 */
    private String firmware;

    /** 本次升级会话 ID，与设备后续回传的 {@code DeviceUpgradeResult} 对应。 */
    private String sessionId;

    /** 说明。 */
    private String note;
}
