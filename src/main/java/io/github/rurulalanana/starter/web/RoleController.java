package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbRole;
import io.github.rurulalanana.starter.mapper.GbRoleMapper;
import io.github.rurulalanana.starter.service.RoleService;
import io.github.rurulalanana.starter.vo.PermissionGroupListVo;
import io.github.rurulalanana.starter.vo.PermissionGroupVo;
import io.github.rurulalanana.starter.vo.PermissionVo;
import io.github.rurulalanana.starter.vo.RoleListVo;
import io.github.rurulalanana.starter.vo.RoleVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色与权限点接口。
 *
 * <pre>
 * GET  /api/v1/role/list          角色列表（含权限点与使用人数）
 * GET  /api/v1/role/permissions   权限点目录（按功能分组，供勾选框渲染）
 * POST /api/v1/role/save          新增 / 修改角色
 * POST /api/v1/role/remove        删除角色
 * POST /api/v1/role/setenable     启用 / 停用角色
 * </pre>
 *
 * <p>读接口要求 {@code user:read}、写接口要求 {@code user:write} ——
 * 角色本身就是「谁能做什么」的配置，与用户管理同权，
 * 单开一个 {@code role:write} 只会让管理员在授权时多勾一个框。
 *
 * <p>权限点目录来自 Java 常量 {@link Permissions}，<b>不落库</b>：
 * 它随代码发布，落库反而会出现「代码里有、库里没有」的漂移。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final GbRoleMapper roleMapper;

    /** 角色列表。 */
    @GetMapping("/list")
    public ApiResponse<RoleListVo> list() {
        StpUtil.checkPermission(Permissions.USER_READ);
        List<RoleVo> list = new ArrayList<>();
        for (GbRole r : roleService.listRoles()) {
            list.add(view(r));
        }
        RoleListVo vo = new RoleListVo();
        vo.setRoleCount(list.size());
        vo.setRoleList(list);
        return ApiResponse.ok(vo);
    }

    /** 权限点目录。 */
    @GetMapping("/permissions")
    public ApiResponse<PermissionGroupListVo> permissions() {
        StpUtil.checkPermission(Permissions.USER_READ);
        // 保持 Permissions.all() 的声明顺序，分组也按首次出现的顺序排
        Map<String, PermissionGroupVo> groups = new LinkedHashMap<>();
        for (Permissions.Def def : Permissions.all()) {
            PermissionGroupVo g = groups.computeIfAbsent(def.group(), k -> {
                PermissionGroupVo created = new PermissionGroupVo();
                created.setGroup(k);
                created.setPermissions(new ArrayList<>());
                return created;
            });
            PermissionVo p = new PermissionVo();
            p.setCode(def.code());
            p.setName(def.name());
            p.setDescription(def.description());
            g.getPermissions().add(p);
        }
        PermissionGroupListVo vo = new PermissionGroupListVo();
        vo.setGroupCount(groups.size());
        vo.setGroupList(new ArrayList<>(groups.values()));
        return ApiResponse.ok(vo);
    }

    /**
     * 新增 / 修改角色。
     *
     * @param permissions 逗号分隔的权限点编码
     */
    @PostMapping("/save")
    public ApiResponse<RoleVo> save(@RequestParam String code,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String description,
                                    @RequestParam(required = false) String permissions,
                                    @RequestParam(required = false) Boolean enable) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        List<String> perms = permissions == null || permissions.isBlank()
                ? List.of()
                : Arrays.stream(permissions.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        GbRole saved = roleService.save(code, name, description, perms, enable);
        return ApiResponse.ok(view(saved));
    }

    /** 删除角色。 */
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String code) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        roleService.remove(code);
        return ApiResponse.ok();
    }

    /** 启用 / 停用角色。 */
    @PostMapping("/setenable")
    public ApiResponse<RoleVo> setEnable(@RequestParam String code,
                                         @RequestParam Boolean enable) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        if (enable == null) {
            throw BizException.badRequest("enable 不能为空");
        }
        return ApiResponse.ok(view(roleService.setEnable(code, enable)));
    }

    /** 实体 → 出参。 */
    private RoleVo view(GbRole role) {
        RoleVo vo = new RoleVo();
        vo.setCode(role.getCode());
        vo.setName(role.getName());
        vo.setDescription(role.getDescription());
        vo.setBuiltin(role.getBuiltin());
        vo.setEnable(role.getEnable());
        boolean superRole = Permissions.ROLE_ADMIN.equals(role.getCode());
        vo.setSuperRole(superRole);
        vo.setPermissions(superRole
                ? new ArrayList<>(Permissions.allCodes())
                : new ArrayList<>(Permissions.expand(roleService.rawPermissions(role.getCode()))));
        vo.setUserCount(roleMapper.countUsersOfRole(role.getCode()));
        vo.setCreateTime(TimeFormats.toDisplay(role.getCreateTime()));
        vo.setUpdateTime(TimeFormats.toDisplay(role.getUpdateTime()));
        return vo;
    }
}
