package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 级联配置列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CascadeListVo {

    /** 配置总数（字段名沿用 GBS 的拼写）。 */
    private Integer casecadeCount;

    /** 配置列表。 */
    private List<CascadeVo> casecadeList;
}
