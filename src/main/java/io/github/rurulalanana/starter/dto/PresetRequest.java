package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 预置位控制参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PresetRequest extends DeviceRef {

    /** 控制指令：{@code set} 设置 / {@code goto} 调用 / {@code remove} 删除。 */
    private String command;

    /** 预置位编号（1~255）。 */
    private Integer preset;

    /** 预置位名称，{@code command=set} 时有效。 */
    private String name;
}
