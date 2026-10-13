package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备列表查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceListQuery extends PageQuery {

    /** 设备类型：{@code device} 国标设备 / {@code decode} 解码器 / {@code all} 全部。 */
    private String deviceType = "all";

    /** 在线状态过滤。 */
    private Boolean online;
}
