package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.List;

/** 权限点（即角色页右侧的一个按钮勾选框）。 */
@Data
public class PermissionVo {

    private String code;

    private String name;

    private String description;

    /** 所属二级菜单名，如「通道控制」。角色页按它把按钮聚成一行。 */
    private String module;

    /**
     * 上级权限点编码（为空表示它是顶部项）。
     *
     * <p>前端据此把「完全控制」与其下具体动作排成父子关系：
     * 勾了上级，下级显示为已包含且不可单独取消。
     */
    private String parent;
}
