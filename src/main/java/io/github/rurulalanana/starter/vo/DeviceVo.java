package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 设备信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceVo {

    /** 设备国标编号。 */
    private String id;

    /** 设备上报名称。 */
    private String name;

    /** 本域自定义名称。 */
    private String customName;

    /** 设备类型，固定 {@code GB}。 */
    private String type;

    private String manufacturer;
    private String model;
    private String firmware;

    /** 通道数。 */
    private Integer channelCount;

    private String civilCode;
    private String address;

    /** 上级节点编号。 */
    private String parentId;

    private java.math.BigDecimal longitude;
    private java.math.BigDecimal latitude;

    /** 云台类型。 */
    private Integer ptzType;

    /** 在线状态字面量：{@code ON} / {@code OFF}。 */
    private String status;

    /** 是否在线。 */
    private Boolean online;

    /** 最近一次注册的对端地址。 */
    private String remoteIp;
    private Integer remotePort;

    /** 信令传输模式。 */
    private String commandTransport;

    /** 流传输模式。 */
    private String mediaTransport;

    private String charset;
    private Integer catalogInterval;
    private Integer subscribeInterval;
    private Boolean catalogSubscribe;
    private Boolean alarmSubscribe;
    private Boolean positionSubscribe;
    private Boolean ptzSubscribe;
    private Boolean recordCenter;

    /** 最近注册时间。 */
    private String lastRegisterAt;

    /** 最近心跳时间。 */
    private String lastKeepaliveAt;

    private String updatedAt;
    private String createdAt;

    /** 通道总数（仅树节点等场景填充）。 */
    private Integer subCount;

    /** 设备子节点数（同 {@link #subCount}）。 */
    private Integer subCountDevice;

    /** 在线子节点数。 */
    private Long onlineSubCount;

    /** 子节点列表（树形返回时填充）。 */
    private List<?> children;
}
