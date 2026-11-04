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
    /** 设备级在线状态，不等于通道可用：下级平台在线时，通道仍可能 Status=OFF（摄像机拔线/断电）。 */
    private Boolean deviceOnline;
    /**
     * 通道是否可用（<b>判断能否点播就用这个</b>）= 设备在线 && 通道 {@code Status=ON}。
     * 早先前端只读 {@code online} 而接口只返回 {@code deviceOnline}，字段名对不上，
     * 弹窗里所有通道恒显示「离线」—— 通道明明能播。
     */
    private Boolean online;
    private Integer channel;
    /** 大类 {@code video/audio/alarm/other}，由 20 位编码第 11~13 位推导（GB/T 28181 附录 D）。 */
    private String channelType;
    /** 类型编码原文（如 {@code 131}），排查时对得上国标表。 */
    private String typeCode;
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
    /**
     * 通道快照地址；无快照时为 {@code null}。指向免登录的
     * {@code /api/v1/cloudrecord/snap/{设备编码}/{通道编码}.jpg}。
     * <b>不在这里现抓</b>：列表可能上千条，逐条拉流截图会打爆设备与带宽；只回已有的那张。
     */
    private String snapshot;
}
