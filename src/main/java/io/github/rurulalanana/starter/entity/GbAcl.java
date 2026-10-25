package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 接入控制条目（对应表 {@code gb_acl}）。
 *
 * <p>黑白名单用于 SIP 注册的来源过滤，与「设备统一接入密码」是两道
 * <b>独立</b>的闸门：密码回答「你是谁」，本表回答「这个来源许不许进」。
 * 访问控制在摘要认证之前生效，因此黑名单设备即使密码正确也进不来 ——
 * 这是密码泄露后仍能拦住已知恶意来源的最后一道防线。
 *
 * <p><b>判定顺序是先黑后白</b>：
 * <ol>
 *   <li>命中黑名单 → 拒绝（黑名单优先级最高，白名单不能给它开后门）；</li>
 *   <li>存在任何白名单且未命中 → 拒绝；</li>
 *   <li>其余放行。</li>
 * </ol>
 * 这个顺序保证「临时拉黑某台设备」不会因为它同时躺在白名单里而失效。
 */
@Data
@TableName("gb_acl")
public class GbAcl implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** BLACK 命中即拒 / WHITE 只放行命中的。 */
    private String listType;

    /** DEVICE 编码 / PREFIX 编码前缀 / IP / CIDR 网段。 */
    private String kind;

    /** 匹配值：设备编码 / 编码前缀 / IPv4 / 网段（如 192.168.1.0/24）。 */
    private String value;

    /** 备注：为什么加这条（半年后只有它还能解释清楚）。 */
    private String remark;

    private LocalDateTime createTime;
}
