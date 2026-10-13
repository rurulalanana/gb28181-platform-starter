package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 通道树节点。
 *
 * <p>字段名沿用前端树组件的约定（{@code id} / {@code name} / {@code children} 等），
 * 因此这里刻意保持小驼峰而非 GBS 的 PascalCase。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeNodeVo {

    /** 节点 ID：{@code <设备编号>} 或 {@code <设备编号>:<通道编号>}。 */
    private String id;

    /** 节点名称。 */
    private String name;

    /** 设备编号。 */
    private String serial;

    /** 通道编号（设备节点为 null）。 */
    private String code;

    /** 在线状态：{@code ON} / {@code OFF}。 */
    private String status;

    /** 是否有子节点。 */
    private Boolean parental;

    /** 云台类型。 */
    private Integer ptzType;

    private String manufacturer;

    /** 父节点编号。 */
    private String parentId;

    /** 子节点数。 */
    private Integer subCount;

    /** 子节点数（设备节点专用，与 {@link #subCount} 同值）。 */
    private Integer subCountDevice;

    /** 在线子节点数。 */
    private Long onlineSubCount;

    /** 子节点（懒加载时为 null）。 */
    private List<TreeNodeVo> children;
}
