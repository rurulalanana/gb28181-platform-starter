package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 录像计划列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordPlanListVo {

    /** 计划总数。 */
    private Integer total;

    /** 计划条目。 */
    private List<RecordPlanVo> rows;
}
