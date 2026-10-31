package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.List;

/** 按功能分组的权限点。 */
@Data
public class PermissionGroupVo {

    /** 分组名，如「设备与通道」。 */
    private String group;

    private List<PermissionVo> permissions;
}
