package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 单个巡航组。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CruiseGroupVo {

    /** 巡航组编号。 */
    private Integer groupId;

    /** 组内预置位列表。 */
    private List<CruisePointVo> pointList;
}
