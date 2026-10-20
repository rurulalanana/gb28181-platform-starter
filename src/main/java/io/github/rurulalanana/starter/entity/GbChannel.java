package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备通道（GB28181 目录项，标准附录 A {@code tg:itemType}）。
 *
 * <p>对应表 {@code gb_channel}。{@code channelId} 同时作为 ZLM 的流名。
 */
@Data
@TableName("gb_channel")
public class GbChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通道国标编号（20 位），全局唯一。 */
    private String channelId;

    /** 所属设备编号。 */
    private String deviceId;

    private String name;

    /** 本域自定义名称，优先展示。 */
    private String customName;

    /** 自定义国标编号（级联时对外暴露的编号）。 */
    private String customId;

    /** 通道序号。 */
    private Integer channelNo;

    private String manufacturer;
    private String model;
    private String owner;
    private String civilCode;
    private String block;
    private String address;

    /** 是否有子设备：0 无 / 1 有。 */
    private Integer parental;

    private String parentId;
    private Integer safetyWay;
    private Integer registerWay;
    private Integer secrecy;

    private String ipAddress;
    private Integer port;
    private String password;

    /** 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。 */
    private Integer ptzType;

    private Integer positionType;
    private Integer roomType;
    private Integer useType;
    private Integer supplyLight;
    private Integer directionType;

    private String resolution;

    /**
     * 码流编号列表（设备上报，如 {@code "0,1"} 表示支持主码流与子码流）。
     *
     * <p>GB/T 28181—2022 附录 A：通道 {@code <Info>} 中的 {@code <StreamNumberList>}。
     * 平台据此知道该通道到底有没有子码流 —— 没有的话配置「码流索引=子码流」是无效的。
     */
    private String streamNumberList;
    private String downloadSpeed;
    private String firmware;
    private String serialNumber;

    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private BigDecimal speed;
    private BigDecimal direction;

    private Integer batteryLevel;
    private Integer signalLevel;

    private String businessGroupId;

    /** 在线状态字面量：{@code ON} / {@code OFF}。 */
    private String status;

    /** 当前直播流 ID，非空表示正在直播。 */
    private String streamId;

    /** 按需直播：无播放者时自动停流。 */
    private Boolean onDemand;

    /** 云端录像开关。 */
    private Boolean cloudRecord;

    /** 音频开关。 */
    private Boolean audio;

    /** 分享开关（免登录观看）。 */
    private Boolean shared;

    private LocalDateTime updateTime;
    private LocalDateTime createTime;

    /** 展示名：自定义名称优先。 */
    public String displayName() {
        return customName != null && !customName.isBlank() ? customName : name;
    }

    /** 是否在线。 */
    public boolean isOnline() {
        return "ON".equalsIgnoreCase(status);
    }
}
