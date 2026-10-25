package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 级联配置（本级作为下级，向上级平台注册）。
 *
 * <p>对应表 {@code gb_cascade}。
 */
@Data
@TableName("gb_cascade")
public class GbCascade implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 级联配置 ID（平台生成）。 */
    private String cascadeId;

    private String name;

    /** 上级平台国标编号（20 位）。 */
    private String parentId;
    private String parentName;
    private String parentIp;
    private Integer parentPort;
    private String parentDomain;
    private String parentPassword;
    private String parentProtocol;

    /** 本级对外暴露的设备编号。 */
    private String localId;
    private String localIp;
    private Integer localPort;

    /** SIP 认证用户名 / 密码。 */
    private String username;
    private String password;

    private Integer keepaliveInterval;
    private Integer keepaliveMaxCount;
    private Integer registerInterval;
    private Integer registerTimeout;

    /** 目录上报时一个包里的 Item 数。 */
    private Integer catalogGroupSize;

    /** 云台控制优先级（0~100）。 */
    private Integer controlPriority;

    /** 流并发数限制，0 表示不限制。 */
    private Integer loadLimit;

    private String charset;

    private Boolean allowControl;
    private Boolean shareAllChannel;
    private Boolean streamKeepalive;

    private Boolean enabled;

    /** 当前是否已注册到上级。 */
    private Boolean online;

    /** 注册有效期（秒）。 */
    private Integer expire;

    private String remark;

    /**
     * 级联上行推流的传输方式。
     *
     * <p>{@code auto}（默认）：跟随上级 INVITE 的 SDP —— 它说 TCP 就用 TCP，
     * 说 UDP 就用 UDP。
     *
     * <p>{@code tcp} / {@code udp}：强制指定。用于上级 SDP 声明与实际可达性
     * 不一致的情况（常见：上级 INVITE 写 UDP，但它那侧 UDP 入站被防火墙拦了，
     * 只有 TCP 能通）。
     */
    private String mediaTransport;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
