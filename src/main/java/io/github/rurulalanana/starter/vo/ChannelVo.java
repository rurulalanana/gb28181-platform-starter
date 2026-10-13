package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 通道信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelVo {

    /** 通道国标编号。 */
    private String id;

    /** 所属设备编号。 */
    private String deviceId;

    /** 所属设备名称。 */
    private String deviceName;

    /** 所属设备自定义名称。 */
    private String deviceCustomName;

    /** 所属设备类型，固定 {@code GB}。 */
    private String deviceType;

    /** 所属设备是否在线。 */
    private Boolean deviceOnline;

    /** 通道序号。 */
    private Integer channel;

    private String name;
    private String customName;
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

    /** 父节点编号。 */
    private String parentId;

    private Integer secrecy;
    private Integer registerWay;

    /** 在线状态字面量。 */
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
    private String downloadSpeed;

    /** 按需直播开关。 */
    private Boolean ondemand;

    /** 音频开关。 */
    private Boolean audioEnable;

    /** 云端录像开关。 */
    private Boolean cloudRecord;

    /** 分享开关。 */
    private Boolean shared;

    /** 子节点数。 */
    private Integer subCount;

    /** 当前流 ID，非空表示正在直播。 */
    private String streamId;

    /** 输出路数。 */
    private Integer numOutputs;

    // ---- 直播中附带的播放地址 ----

    private String webrtc;
    private String flv;
    private String hls;
    private String rtsp;
    private String rtmp;
}
