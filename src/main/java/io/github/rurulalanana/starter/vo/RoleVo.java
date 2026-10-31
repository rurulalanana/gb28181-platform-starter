package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 角色。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleVo {

    /** 角色编码（用户表里存的就是它）。 */
    private String code;

    private String name;

    private String description;

    /** 内置角色：不可删除。 */
    private Boolean builtin;

    private Boolean enable;

    /** 是否管理员角色（权限恒为全部，页面据此禁用勾选框）。 */
    private Boolean superRole;

    /**
     * 该角色拥有的权限点，已展开。
     *
     * <p>{@code admin} 返回的是<b>全部权限点编码</b>而不是 {@code "*"}，
     * 前端勾选框可以直接用同一套 {@code includes} 逻辑渲染。
     */
    private List<String> permissions;

    /** 使用该角色的用户数（删除前提示用）。 */
    private Long userCount;

    private String createTime;
    private String updateTime;
}
