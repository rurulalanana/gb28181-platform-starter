package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 状态变更记录。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusLogVo {

    /** 记录 ID。 */
    private String id;

    /** 设备编号。 */
    private String serial;

    /** 通道编号。 */
    private String code;

    /** 新状态。 */
    private String status;

    /** 描述。 */
    private String description;

    /** 记录时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String createdAt;
}
