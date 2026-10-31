package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单外观覆盖（对应表 {@code gb_menu}）。
 *
 * <p><b>目录与菜单项同一张表</b>，用 {@link #menuType} 区分、{@link #parentKey}
 * 自关联。参照若依 {@code sys_menu} 的形态：目录就是表里的一行，
 * 不需要单独的分组表 —— 改分组名只是 UPDATE 一行，不会有「改一半」的风险。
 *
 * <p>{@link #menuKey} 做业务主键，目录与菜单项的编码天生处在不同命名空间
 * （目录 {@code monitor/resource/...}，菜单项 {@code dashboard/devices/...}），
 * 所以不存在「路由名与分组码争主键」的问题。
 *
 * <p><b>只存差异</b>：字段为 {@code null}（或整行不存在）= 用前端代码里的默认值。
 * 这样默认菜单永远跟着代码走，库里只记录运维改过的那几项，
 * 代码回滚或删除菜单项时不会留下无法解释的残留。
 */
@Data
@TableName("gb_menu")
public class GbMenu implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目录：分组编码；菜单：Vue 路由 name。 */
    @TableId
    private String menuKey;

    /** 菜单项所属目录的 {@code menuKey}；{@code null} = 用代码里的默认分组。 */
    private String parentKey;

    /** {@link #TYPE_DIRECTORY} 或 {@link #TYPE_MENU}。 */
    private String menuType;

    /** 显示名（目录行必填，菜单项可选）。 */
    private String title;

    /** 图标（Element Plus 图标组件名）。 */
    private String icon;

    /** 同级排序。 */
    private Integer sortOrder;

    /** 是否在菜单中隐藏（不影响路由与权限）。 */
    private Boolean hidden;

    private LocalDateTime updateTime;

    /** 目录/分组。 */
    public static final String TYPE_DIRECTORY = "D";
    /** 菜单项。 */
    public static final String TYPE_MENU = "M";
}
