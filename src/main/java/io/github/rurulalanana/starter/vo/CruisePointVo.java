package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 巡航轨迹上的一个点（预置位）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CruisePointVo {

    /** 预置位编号。 */
    private Integer preset;

    /** 停留时间（秒）。 */
    private Integer stay;

    /** 巡航速度。 */
    private Integer speed;
}
