package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.entity.GbRole;
import io.github.rurulalanana.starter.mapper.GbRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色与权限解析。
 *
 * <p>整表缓存在内存：{@link #permissionsOf} 在每个写请求的权限校验里都会被调用
 * （{@code WritePermissionInterceptor}），每次查库会让一个批量操作打出几十次角色查询；
 * 角色数据十几条且只在管理页改动，写操作后整体失效即可（「改完下一次请求生效」本就是期望行为）。
 * 重建而非逐条更新的理由：角色变更同时影响权限集合与启用状态，
 * 一条 SELECT + 每角色一条 SELECT 是毫秒级，宁可粗放也不留「改了没生效」的隐蔽 bug。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    /** 角色编码允许的字符：小写字母、数字、下划线、中划线，2~32 位。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");

    private final GbRoleMapper roleMapper;

    /**
     * 权限缓存（角色编码 → 权限点）：{@code null} 表示尚未加载或已失效，下次读取时重建。
     * 整体替换引用（不可变 Map），读路径无需加锁。
     */
    private volatile Map<String, Set<String>> grantCache;

    // ==================== 读取 ====================

    /** 全部角色（按内置优先、编码排序）。 */
    public List<GbRole> listRoles() {
        return roleMapper.selectList(Wrappers.<GbRole>lambdaQuery()
                .orderByDesc(GbRole::getBuiltin)
                .orderByAsc(GbRole::getCode));
    }

    /** 某角色拥有的权限点（原始值，可能含 {@code *} 与已下线的编码）。 */
    public List<String> rawPermissions(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return new ArrayList<>();
        }
        return roleMapper.selectPermissions(roleCode.trim().toLowerCase(Locale.ROOT));
    }

    /** 按角色编码取 + 中文名，供页面展示。 */
    public Map<String, String> roleNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (GbRole r : listRoles()) {
            out.put(r.getCode(), r.getName());
        }
        return out;
    }

    /**
     * 解析一组角色编码对应的展开后权限点。停用的角色不贡献权限；不存在的编码直接忽略
     * （用户表里可能残留被删角色的编码，应退化为「没有该角色的权限」而不是报错）。
     */
    public Set<String> permissionsOf(Collection<String> roleCodes) {
        Set<String> out = new LinkedHashSet<>();
        if (roleCodes == null || roleCodes.isEmpty()) {
            return out;
        }
        Map<String, Set<String>> cache = grants();
        for (String raw : roleCodes) {
            String code = Permissions.normalize(raw);
            if (code.isEmpty()) {
                continue;
            }
            Set<String> grant = cache.get(code);
            if (grant != null) {
                out.addAll(grant);
            }
        }
        return out;
    }

    /** 当前登录用户的权限点（{@code StpUtil} 侧也会走这条路，见 SaTokenPermissionProvider）。 */
    public Set<String> permissionsOfUserRoles(String commaSeparatedRoles) {
        return permissionsOf(Permissions.splitRoles(commaSeparatedRoles));
    }

    /** 权限缓存（惰性重建）。 */
    private Map<String, Set<String>> grants() {
        Map<String, Set<String>> snapshot = grantCache;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (grantCache != null) {
                return grantCache;
            }
            Map<String, Set<String>> built = new LinkedHashMap<>();
            for (GbRole role : listRoles()) {
                if (Boolean.FALSE.equals(role.getEnable())) {
                    continue;
                }
                built.put(role.getCode(), Permissions.expand(rawPermissions(role.getCode())));
            }
            grantCache = Map.copyOf(built);
            log.debug("角色权限缓存已重建：{} 个启用角色", grantCache.size());
            return grantCache;
        }
    }

    /** 丢弃权限缓存。任何角色变更后都必须调用。 */
    public void invalidate() {
        grantCache = null;
    }

    // ==================== 写入 ====================

    /**
     * 生效角色编码校验：返回去重后的合法编码，遇到不存在的角色直接报错。
     * 用户表存的是角色编码，写入一个不存在的编码会让该用户「什么权限都没有」且查不出原因。
     */
    public List<String> requireExistingRoles(Collection<String> roleCodes) {
        List<String> wanted = new ArrayList<>();
        if (roleCodes != null) {
            for (String code : roleCodes) {
                String c = Permissions.normalize(code);
                if (!c.isEmpty() && !wanted.contains(c)) {
                    wanted.add(c);
                }
            }
        }
        if (wanted.isEmpty()) {
            return wanted;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (GbRole r : listRoles()) {
            existing.add(r.getCode());
        }
        for (String code : wanted) {
            if (!existing.contains(code)) {
                throw BizException.badRequest("角色不存在：" + code);
            }
        }
        return wanted;
    }

    /**
     * 新增 / 修改角色。三条硬约束：{@code admin} 的权限恒为通配（它是「永远有管理员」的锚点，
     * 允许改空就能把自己锁死）；权限点必须是已登记的编码（防前端传错字面量把角色变成空壳）；
     * 至少一个权限点（零权限角色在页面上无法与「配置出错」区分）。
     * 没有「改编码」操作：{@code code} 是主键，用户表存的就是它。
     */
    @Transactional
    public GbRole save(String code, String name, String description, Collection<String> permissionCodes,
                       Boolean enable) {
        String c = Permissions.normalize(code);
        if (c.isEmpty()) {
            throw BizException.badRequest("角色编码不能为空");
        }
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw BizException.badRequest("角色编码只能是小写字母开头的字母/数字/下划线/中划线，长度 2~32");
        }
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("角色名称不能为空");
        }
        GbRole existing = roleMapper.selectById(c);
        boolean created = existing == null;

        List<String> wanted = normalizePermissions(permissionCodes);
        if (Permissions.ROLE_ADMIN.equals(c)) {
            // 管理员恒为通配：忽略传入值，避免被改成空集后无人能登录管理
            wanted = List.of(Permissions.ALL);
        }
        if (wanted.isEmpty()) {
            throw BizException.badRequest("至少要给角色勾选一个权限点，否则该角色什么都做不了");
        }

        GbRole role = created ? new GbRole() : existing;
        role.setCode(c);
        role.setName(name.trim());
        role.setDescription(description == null ? null : description.trim());
        if (created) {
            role.setBuiltin(false);
            role.setCreateTime(LocalDateTime.now());
        }
        role.setEnable(enable == null ? Boolean.TRUE : enable);
        role.setUpdateTime(LocalDateTime.now());
        if (created) {
            roleMapper.insert(role);
        } else {
            roleMapper.updateById(role);
        }

        // 先删后插：权限点最多二十几个，比逐条 diff 简单且不会漏
        roleMapper.deletePermissions(c);
        for (String p : wanted) {
            roleMapper.insertPermission(c, p);
        }
        invalidate();
        log.info("角色已保存: code={}, name={}, permissions={}, 新建={}", c, role.getName(), wanted, created);
        return role;
    }

    /** 规范权限点：校验是否登记、去重、保序。 */
    private List<String> normalizePermissions(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return new ArrayList<>();
        }
        for (String r : raw) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String p = r.trim();
            if (!Permissions.isKnown(p)) {
                throw BizException.badRequest("未知的权限点：" + p);
            }
            out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** 删除角色。内置角色与「仍被用户使用」的角色不允许删除。 */
    @Transactional
    public void remove(String code) {
        String c = Permissions.normalize(code);
        GbRole role = roleMapper.selectById(c);
        if (role == null) {
            throw BizException.notFound("角色不存在：" + c);
        }
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw BizException.badRequest("内置角色不能删除：" + c);
        }
        long users = roleMapper.countUsersOfRole(c);
        if (users > 0) {
            throw BizException.badRequest("仍有 " + users + " 个用户使用该角色，请先改掉他们的角色");
        }
        roleMapper.deletePermissions(c);
        roleMapper.deleteById(c);
        invalidate();
        log.info("角色已删除: code={}", c);
    }

    /** 启用 / 停用角色。{@code admin} 不允许停用。 */
    @Transactional
    public GbRole setEnable(String code, boolean enable) {
        String c = Permissions.normalize(code);
        GbRole role = roleMapper.selectById(c);
        if (role == null) {
            throw BizException.notFound("角色不存在：" + c);
        }
        if (Permissions.ROLE_ADMIN.equals(c) && !enable) {
            throw BizException.badRequest("管理员角色不能停用，否则将无人能管理系统");
        }
        role.setEnable(enable);
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
        invalidate();
        log.info("角色{}: code={}", enable ? "已启用" : "已停用", c);
        return role;
    }
}
