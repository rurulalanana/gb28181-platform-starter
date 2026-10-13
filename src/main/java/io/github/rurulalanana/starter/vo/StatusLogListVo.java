package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 状态变更记录列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusLogListVo {

    /** 记录总数。 */
    private Integer logCount;

    /** 保留天数。 */
    private Integer logReserveDays;

    /** 记录列表。 */
    private List<StatusLogVo> logList;
}
