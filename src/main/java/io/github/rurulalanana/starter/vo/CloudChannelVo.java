package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 云端录像通道（{@code cloudrecord/querychannels}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudChannelVo {

    private String serial;
    private String code;
    private String name;
    private String customName;
    private String status;
    private String deviceName;
    private String deviceCustomName;
    private Boolean deviceOnline;
    private String updatedAt;
    private String createdAt;
}
