package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.stp.StpInterface;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
import io.github.rurulalanana.starter.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Sa-Token 的权限数据源。
 *
 * <p>角色取自 {@code gb_user.role}（逗号分隔，引用 {@code gb_role.code}），
 * 权限点由 {@link RoleService} 把角色展开成 {@code gb_role_permission} 里的编码。
 *
 * <h3>为什么这里不抛异常</h3>
 *
 * <p>Sa-Token 在每次 {@code checkPermission} / {@code checkRole} 时都会回调本类。
 * 只要这里抛异常（例如用户已被删除、库连接抖动），整个请求就会变成 500，
 * 而真实语义应该是「没有权限 → 403」或「未登录 → 401」。
 * 因此取不到角色/权限时一律<b>返回空集合</b>：失败方向指向「权限更少」，
 * 这是安全的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaTokenPermissionProvider implements StpInterface {

    private final GbUserMapper userMapper;
    private final RoleService roleService;

    /**
     * 权限列表（已展开）。
     *
     * <p>拥有 {@code *} 的角色（即 admin）会展开成当前目录的<b>全部编码</b>，
     * 而不是返回 {@code "*"} —— 前端的 {@code permissions.includes('device:write')}
     * 才能直接生效，不必在每个调用点都记得处理通配符。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        GbUser user = findUser(loginId);
        if (user == null || Boolean.FALSE.equals(user.getEnable())) {
            return new ArrayList<>();
        }
        return new ArrayList<>(roleService.permissionsOf(Permissions.splitRoles(user.getRole())));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        GbUser user = findUser(loginId);
        if (user == null || Boolean.FALSE.equals(user.getEnable())) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Permissions.splitRoles(user.getRole()));
    }

    /** 按登录主键取用户；任何解析失败都当作「查不到」。 */
    private GbUser findUser(Object loginId) {
        if (loginId == null) {
            return null;
        }
        try {
            return userMapper.selectById(Long.parseLong(String.valueOf(loginId)));
        } catch (NumberFormatException e) {
            return null;
        } catch (RuntimeException e) {
            log.warn("读取用户失败（按「无权限」处理）：loginId={}, {}", loginId, e.toString());
            return null;
        }
    }

    /**
     * 便捷方法：不经过 Sa-Token 上下文，直接算某个用户角色串的权限。
     *
     * <p>给 {@code SystemController#userInfo} 复用，避免为了拿一次权限清单
     * 而依赖 {@code StpUtil} 的线程上下文（单元测试里没有上下文可用）。
     */
    public Collection<String> permissionsOfRoles(String commaSeparatedRoles) {
        return roleService.permissionsOfUserRoles(commaSeparatedRoles);
    }
}
