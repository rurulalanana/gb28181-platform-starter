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
    /**
     * 所属设备是否在线。
     *
     * <p>注意这是<b>设备</b>级状态，不等于通道可用：下级平台在线时，
     * 它上报的通道仍可能是 {@code Status=OFF}（摄像机被拔线、断电）。
     * 判断「这一路能不能看」要用 {@link #online}。
     */
    private Boolean deviceOnline;
    /**
     * 通道是否可用（<b>判断能否点播就用这个</b>）。
     *
     * <p>= 设备在线 && 通道 {@code Status=ON}。
     *
     * <p>改造前前端直接读 {@code online}，而后端只返回了 {@code deviceOnline}，
     * 字段名对不上，弹窗里所有通道恒显示「离线」—— 通道明明能播。
     */
    private Boolean online;
    private Integer channel;
    /**
     * 通道类型大类：{@code video} / {@code audio} / {@code alarm} / {@code other}。
     *
     * <p>由 20 位国标编码第 11~13 位的类型编码推导（GB/T 28181 附录 D）。
     * 上级平台的「通道类型」筛选就按这个维度分类，而不是列 20 种细类。
     */
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
     * 通道快照地址；无快照时为 {@code null}。
     *
     * <p>指向 {@code /api/v1/cloudrecord/snap/{设备编码}/{通道编码}.jpg}（免登录）。
     * <b>不在这里现抓</b>：列表可能几百上千条，逐条拉流截图会瞬间打爆设备和带宽。
     * 快照由用户在播放页手动抓拍或报警联动产生，这里只回已有的那张。
     */
    private String snapshot;
}
