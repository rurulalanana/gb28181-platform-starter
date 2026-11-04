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

    /**
     * 接入方式（{@code gb28181} / {@code dahua}），来自 {@code gb_device.source}。
     *
     * <p>前端要区分「国标设备」和「SDK 直连设备」：
     * 国标设备在线时不能删（它立刻会重新 REGISTER），
     * 而 SDK 设备的连接是平台主动发起的，在线也能删（删之前把会话登出）。
     * 没有这个字段前端只能靠猜。
     */
    private String source;

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

    private Boolean online;

    /** 最近一次注册的对端地址。 */
    private String remoteIp;
    private Integer remotePort;

    /**
     * 设备信令出口地址，形如 {@code udp://183.201.190.57:5060}。合成一个可复制粘贴的地址，
     * 排查跨网/NAT 问题时比拆成三列更省事。
     */
    private String endpoint;

    private String commandTransport;

    private String mediaTransport;

    /** TCP 流传输模式：{@code passive} / {@code active}，需与 {@link #mediaTransport} 组合看。 */
    private String mediaTransportMode;

    /** 码流索引：{@code auto} 自动 / {@code streamnumber:0} 主码流 / {@code streamnumber:1} 子码流。 */
    private String streamIndex;

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

    private Integer subCount;

    private Integer subCountDevice;

    private Long onlineSubCount;

    /** 子节点列表（树形返回时填充）。 */
    private List<?> children;
}
