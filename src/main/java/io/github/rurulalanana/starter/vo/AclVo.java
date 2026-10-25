package io.github.rurulalanana.starter.vo;

import lombok.Data;

/**
 * 接入控制规则（出参）。
 *
 * <p>字段全部为强类型，不使用 {@code Map<String,Object>} 拼装（项目约定）。
 */
@Data
public class AclVo {

    /** 主键，删除时用。 */
    private Long id;

    /** BLACK 黑名单 / WHITE 白名单。 */
    private String listType;

    /** DEVICE 设备编码 / PREFIX 编码前缀 / IP 地址 / CIDR 网段。 */
    private String kind;

    /** 匹配值。 */
    private String value;

    /** 备注：为什么加这条。 */
    private String remark;

    /** 添加时间（展示格式 yyyy-MM-dd HH:mm:ss）。 */
    private String createTime;
}
