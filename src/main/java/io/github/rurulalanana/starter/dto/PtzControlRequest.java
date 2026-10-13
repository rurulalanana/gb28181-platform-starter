package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 云台控制参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PtzControlRequest extends DeviceRef {

    /** 控制指令：left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop。 */
    private String command;

    /** 速度（0~255），默认 129。 */
    private Integer speed = 129;
}
