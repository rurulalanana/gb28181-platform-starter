package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像计划条目。
 *
 * <p>字段名与 LiveGBS 的 {@code /cloudrecord/plan/list} 保持一致
 * （{@code ID} / {@code Name} / {@code Plan} / {@code Enable} /
 * {@code CreatedAt} / {@code UpdatedAt}），便于对照排查。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordPlanVo {

    /** 计划 ID。 */
    private String ID;

    /** 计划名称。 */
    private String Name;

    /** 每周时段规则（JSON 字符串）。 */
    private String Plan;

    /** 是否启用。 */
    private Boolean Enable;

    /** 创建时间。 */
    private String CreatedAt;

    /** 更新时间。 */
    private String UpdatedAt;

    /** 已关联的通道数量。 */
    private Integer channelCount;
}
