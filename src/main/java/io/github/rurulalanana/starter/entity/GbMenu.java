package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单节点（对应表 {@code gb_menu}）。
 *
 * <p><b>目录、菜单、按钮三种节点同一张表</b>，用 {@link #menuType} 区分、{@link #parentKey}
 * 自关联，参照若依 {@code sys_menu} 的形态：
 * <ul>
 *   <li>{@link #TYPE_DIRECTORY}：侧栏分组，没有路由；</li>
 *   <li>{@link #TYPE_MENU}：可打开的页面，{@link #path} + {@link #component} 决定路由；</li>
 *   <li>{@link #TYPE_BUTTON}：只有 {@link #perms}，用于授权可视化，不产生路由。</li>
 * </ul>
 *
 * <p>整棵菜单树以本表为准：前端 {@code router/routes.js} 只在种子版本变化时补写
 * 缺失的目录/菜单行（组件清单只有前端知道），按钮行由后端按
 * {@code Permissions} 常量补写。这样运维在「菜单管理」页删掉的行不会被每次重启种回来。
 */
@Data
@TableName("gb_menu")
public class GbMenu implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目录：分组编码；菜单：Vue 路由 name；按钮：权限标识。 */
    @TableId
    private String menuKey;

    /** 上级节点：菜单挂目录、按钮挂菜单；目录为 {@code null}。 */
    private String parentKey;

    /** 见 {@link #TYPE_DIRECTORY} / {@link #TYPE_MENU} / {@link #TYPE_BUTTON}。 */
    private String menuType;

    /** 显示名。 */
    private String title;

    /** 图标（Element Plus 图标组件名）。 */
    private String icon;

    /** 同级排序。 */
    private Integer sortOrder;

    /** 是否在侧栏隐藏（隐藏仍可经直连 URL 打开，权限守卫照常生效）。 */
    private Boolean hidden;

    /** 路由地址（菜单行），如 {@code /users}。 */
    private String path;

    /** 组件路径（菜单行），如 {@code views/UserView.vue}，前端用 {@code import.meta.glob} 解析。 */
    private String component;

    /** 权限标识（菜单/按钮行），如 {@code device:write}。 */
    private String perms;

    /** 见 {@link #STATUS_ENABLED} / {@link #STATUS_DISABLED}。 */
    private Integer status;

    private LocalDateTime updateTime;

    /** 目录/分组。 */
    public static final String TYPE_DIRECTORY = "D";
    /** 菜单项（一个页面）。 */
    public static final String TYPE_MENU = "M";
    /** 按钮（一个权限标识）。 */
    public static final String TYPE_BUTTON = "B";

    /** 正常：进侧栏并注册路由。 */
    public static final int STATUS_ENABLED = 0;
    /** 停用：不进侧栏、不注册路由。 */
    public static final int STATUS_DISABLED = 1;
}
