package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备通道（GB28181 目录项，标准附录 A {@code tg:itemType}，表 {@code gb_channel}）。
 * {@code channelId} 同时作为 ZLM 的流名。
 */
@Data
@TableName("gb_channel")
public class GbChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通道国标编号（20 位），全局唯一。 */
    private String channelId;

    private String deviceId;

    private String name;

    /** 本域自定义名称，优先展示。 */
    private String customName;

    /** 自定义国标编号（级联时对外暴露的编号）。 */
    private String customId;

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
     * 码流编号列表（设备上报，如 {@code "0,1"}）。GB/T 28181—2022 附录 A 的 {@code <StreamNumberList>}，
     * 平台据此判断通道到底有没有子码流 —— 没有时配「码流索引=子码流」是无效的。
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

    private String status;

    private String streamId;

    private Boolean onDemand;

    /** 云端录像开关（与按需直播互斥）。 */
    private Boolean cloudRecord;

    private Boolean audio;

    private Boolean shared;

    private LocalDateTime updateTime;
    private LocalDateTime createTime;

    public String displayName() {
        return customName != null && !customName.isBlank() ? customName : name;
    }

    public boolean isOnline() {
        return "ON".equalsIgnoreCase(status);
    }
}
