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

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
