package io.github.rurulalanana.starter.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 新增/修改菜单节点的请求体。
 *
 * <p>字段与「菜单管理」页的表单一一对应，按 {@code menuType} 分派校验：
 * <ul>
 *   <li>D 目录：{@code title} 必填，其余路由类字段忽略；</li>
 *   <li>M 菜单：{@code parentKey}/{@code title}/{@code path}/{@code component} 必填；</li>
 *   <li>B 按钮：{@code parentKey}/{@code title}/{@code perms} 必填。</li>
 * </ul>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MenuSaveRequest {

    /**
     * 主键。
     *
     * <p>为空 = 新增（服务端按类型生成 {@code m-/b-/d-} 前缀的键）；
     * 非空 = 修改。修改时<b>不允许改键</b> —— 键是路由 name，改了等于换了页面。
     */
    private String menuKey;

    private String parentKey;

    /** D 目录 / M 菜单 / B 按钮。 */
    private String menuType;

    private String title;

    private String icon;

    private Integer sortOrder;

    private Boolean hidden;

    /** 路由地址（M）：必须以 {@code /} 开头。 */
    private String path;

    /** 组件路径（M）：形如 {@code views/UserView.vue}。 */
    private String component;

    /** 权限标识（M/B）：形如 {@code device:write}。 */
    private String perms;

    /** 0 正常 / 1 停用。 */
    private Integer status;
}
