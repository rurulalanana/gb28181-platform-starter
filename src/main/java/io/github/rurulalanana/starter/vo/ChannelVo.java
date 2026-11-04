package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 通道信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelVo {

    private String id;

    private String deviceId;

    private String deviceName;

    private String deviceCustomName;

    /** 所属设备类型，固定 {@code GB}。 */
    private String deviceType;

    /**
     * 所属设备的<b>接入方式</b>：{@code gb28181} / {@code dahua}。
     *
     * <p>{@code deviceType} 是历史字段（恒为 {@code GB}），不能用来判协议。
     * 前端据此决定显不显示「相机参数」tab —— 那是 SDK 直连设备才有的能力。
     */
    private String source;

    /** 所属设备是否在线（通道自身可用性看 status）。 */
    private Boolean deviceOnline;

    private Integer channel;

    private String name;
    private String customName;
    /** 自定义国标编号（级联时对外暴露）。 */
    private String customId;
    private String block;
    private String civilCode;
    private String address;
    private String manufacturer;
    private String model;
    private String owner;
    private String firmware;
    private String serialNumber;
    private String ipAddress;
    private Integer port;

    /** 是否有子设备：0 无 / 1 有。 */
    private Integer parental;

    private String parentId;

    private Integer secrecy;
    private Integer registerWay;

    private String status;

    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private BigDecimal speed;
    private BigDecimal direction;

    /** 云台类型。 */
    private Integer ptzType;

    private Integer batteryLevel;
    private Integer signalLevel;
    private String resolution;

    /** 码流编号列表（设备上报，如 {@code "0,1"}），据此判断是否支持子码流。 */
    private String streamNumberList;

    /**
     * 该通道能否切换码流（前端据此决定显不显示码流选择器）。
     *
     * <p>判据见 {@code DeviceController#streamSwitchable}：SDK 直连设备恒支持，
     * 国标设备看目录里有没有上报两路以上的 {@code StreamNumberList}。
     */
    private Boolean streamSwitchable;
    private String downloadSpeed;

    /** 按需直播（与云端录像互斥）。 */
    private Boolean ondemand;

    private Boolean audioEnable;

    /** 云端录像开关（与按需直播互斥）。 */
    private Boolean cloudRecord;

    private Boolean shared;

    private Integer subCount;

    private String updatedAt;

    private String createdAt;

    /** 当前流 ID，非空表示正在直播。 */
    private String streamId;

    private Integer numOutputs;

    // ---- 直播中附带的播放地址 ----

    private String webrtc;
    private String flv;
    private String hls;
    private String rtsp;
    private String rtmp;
}
