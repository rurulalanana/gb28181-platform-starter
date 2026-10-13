package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 位置记录列表。
 *
 * <p>位置记录由设备通过 {@code MobilePosition} 上报，未开启位置订阅时为空列表。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PositionLogListVo {

    /** 记录总数。 */
    private Integer logCount;

    /** 记录列表。 */
    private java.util.List<PositionLogVo> logList;
}
