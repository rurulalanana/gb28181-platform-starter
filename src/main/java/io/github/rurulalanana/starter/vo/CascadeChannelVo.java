package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 级联通道（共享视图）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CascadeChannelVo {

    /** 共享到的级联 ID；未共享时为 null。 */
    private String cascadeId;

    private String id;
    private String deviceId;
    private String name;
    private String customName;
    private String customId;
    private Boolean deviceOnline;
    private Integer channel;
    private String manufacturer;
    private String model;
    private String civilCode;
    private String address;
    private String parentId;
    private Integer secrecy;
    private Integer registerWay;
    private String status;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer ptzType;
    private Boolean ondemand;
    private Boolean audioEnable;
}
