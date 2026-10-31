package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.List;

/** 角色列表。 */
@Data
public class RoleListVo {

    private Integer roleCount;

    private List<RoleVo> roleList;
}
