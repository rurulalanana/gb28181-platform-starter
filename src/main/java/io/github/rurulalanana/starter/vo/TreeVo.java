package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 树形接口出参（通道树 / 分组树）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeVo {

    /** 节点列表。 */
    private List<TreeNodeVo> nodeList;
}
