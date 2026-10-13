package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设备升级应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceUpgradeVo {

    /** 升级结果：{@code PENDING} 表示已下发、等待设备通知。 */
    private String upgradeResult;

    /** 固件版本。 */
    private String firmware;

    /** 说明。 */
    private String note;
}
