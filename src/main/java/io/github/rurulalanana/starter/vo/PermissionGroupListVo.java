package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.List;

/** 权限点目录（按功能分组）。 */
@Data
public class PermissionGroupListVo {

    private Integer groupCount;

    private List<PermissionGroupVo> groupList;
}
