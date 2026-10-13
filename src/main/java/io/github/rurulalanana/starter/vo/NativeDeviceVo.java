package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：注册表中的在线设备（{@code GET /api/devices}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeDeviceVo {

    private String deviceId;
    private String address;
    private Boolean online;
    private Long registerTime;
}
