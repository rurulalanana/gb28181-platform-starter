package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 巡航控制参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NavigateRequest extends DeviceRef {

    /** 控制指令：query/add/remove/setspeed/setstay/start/stop。 */
    private String command;

    /** 巡航组编号（1~255）。 */
    private Integer groupid;

    /** 预置位编号（0~255）。{@code remove} 且 {@code preset=0} 时删除整条巡航。 */
    private Integer preset;

    /** 巡航速度（1~4095）。 */
    private Integer speed;

    /** 巡航停留时间（秒，1~4095）。 */
    private Integer stay;

    /** 巡航组名称（GB/T 28181—2022 新增）。 */
    private String name;
}
