package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设备列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceListVo {

    /** 过滤后的设备总数。 */
    private Integer deviceCount;

    /** 当前页设备。 */
    private java.util.List<DeviceVo> deviceList;
}
