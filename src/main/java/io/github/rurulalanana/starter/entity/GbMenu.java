package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单节点（表 {@code gb_menu}）。目录、菜单、按钮同一张表，用 {@link #menuType} 区分、
 * {@link #parentKey} 自关联（参照若依 {@code sys_menu}）：D 侧栏分组、M 可打开页面、B 只有权限标识。
 *
 * <p>整棵菜单树以本表为准：前端只在种子版本变化时补写缺失的目录/菜单（组件清单只有前端知道），
 * 按钮行由后端按 {@code Permissions} 常量补写。运维在「菜单管理」删掉的行不会被重启种回来。
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

    private String title;

    private String icon;

    private Integer sortOrder;

    /** 是否在侧栏隐藏（隐藏仍可经直连 URL 打开，权限守卫照常生效）。 */
    private Boolean hidden;

    /** 路由地址（菜单行），如 {@code /users}。 */
    private String path;

    /** 组件路径（菜单行），如 {@code views/UserView.vue}，前端用 {@code import.meta.glob} 解析。 */
    private String component;

    /** 权限标识（菜单/按钮行），如 {@code device:write}。 */
    private String perms;

    private Integer status;

    private LocalDateTime updateTime;

    /** 目录/分组。 */
    public static final String TYPE_DIRECTORY = "D";
    public static final String TYPE_MENU = "M";
    /** 按钮（一个权限标识），不产生路由。 */
    public static final String TYPE_BUTTON = "B";

    public static final int STATUS_ENABLED = 0;
    /** 停用：不进侧栏、不注册路由。 */
    public static final int STATUS_DISABLED = 1;
}
