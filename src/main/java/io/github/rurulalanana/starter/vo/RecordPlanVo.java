package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像计划条目。
 *
 * <p>字段一律<b>小写驼峰</b>，与平台其余接口（{@code ChannelVo} /
 * {@code DeviceVo} / {@code AlarmVo} 等）保持一致。
 *
 * <p><b>不要写成 {@code ID / Name / Plan} 这种大写开头</b>：Lombok 会生成
 * {@code getID()}，Jackson 按 getter 推导属性名时会 decapitalize 成 {@code "id"} ——
 * 于是「Java 字段名」和「实际 JSON key」不一致，前端照字段名读会拿到
 * {@code undefined}。实测后果：计划列表名称空白、时段全灭、编辑弹窗回显不出内容；
 * 「关联通道」拿到 undefined 的 id，通道表格为空，拖拽选择看起来也失效。
 *
 * <p>要改 JSON 契约就显式加 {@code @JsonProperty}，不要依赖字段命名。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordPlanVo {

    /** 计划 ID。 */
    private String id;

    /** 计划名称。 */
    private String name;

    /** 每周时段规则（JSON 字符串）。 */
    private String plan;

    /** 是否启用。 */
    private Boolean enable;

    /** 创建时间。 */
    private String createdAt;

    /** 更新时间。 */
    private String updatedAt;

    /** 已关联的通道数量。 */
    private Integer channelCount;
}
