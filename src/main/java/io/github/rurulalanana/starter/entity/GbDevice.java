package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 国标设备（GB28181 设备，通常为 NVR / 平台 / 直连摄像机）。
 *
 * <p>对应表 {@code gb_device}。一个设备下挂若干 {@link GbChannel 通道}。
 */
@Data
@TableName("gb_device")
public class GbDevice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备国标编号（20 位）。 */
    private String deviceId;

    /** 设备上报名称。 */
    private String name;

    /** 本域自定义名称，优先展示。 */
    private String customName;

    private String manufacturer;
    private String model;
    private String firmware;

    /** 通道数（目录查询后的统计值）。 */
    private Integer channelCount;

    private String civilCode;
    private String address;
    private String parentId;

    private BigDecimal longitude;
    private BigDecimal latitude;

    /** 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。 */
    private Integer ptzType;

    /** 在线状态字面量：{@code ON} / {@code OFF}。 */
    private String status;

    private Boolean online;

    /** 最近一次注册的对端地址。 */
    private String remoteIp;
    private Integer remotePort;

    /** 信令传输模式：{@code UDP} / {@code TCP}。 */
    private String transport;

    /** 流传输模式：{@code UDP} / {@code TCP}。 */
    private String mediaTransport;

    /**
     * TCP 流传输模式：{@code passive} 被动收流 / {@code active} 主动推流。
     *
     * <p>仅在 {@code mediaTransport=TCP} 时有意义；UDP 固定被动。
     */
    private String mediaTransportMode;

    private String charset;
    private String password;

    /** 目录抓取周期（秒），0 表示不周期抓取。 */
    private Integer catalogInterval;

    /** 订阅周期（秒），0 表示不订阅。 */
    private Integer subscribeInterval;

    private Boolean catalogSubscribe;
    private Boolean alarmSubscribe;
    private Boolean positionSubscribe;
    private Boolean ptzSubscribe;

    /** 设备录像是否走中心检索。 */
    private Boolean recordCenter;

    private LocalDateTime registerTime;
    private LocalDateTime keepaliveTime;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;

    /** 来源：{@code device} 自动注册 / {@code manual} 手工添加。 */
    private String source;

    @TableField("extra")
    private String extra;

    /** 展示名：自定义名称优先。 */
    public String displayName() {
        return customName != null && !customName.isBlank() ? customName : name;
    }
}
