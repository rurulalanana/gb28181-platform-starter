package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
import io.github.rurulalanana.starter.service.RoleService;
import io.github.rurulalanana.starter.service.UserService;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.IdVo;
import io.github.rurulalanana.starter.vo.UserListVo;
import io.github.rurulalanana.starter.vo.UserVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理接口。
 *
 * <pre>
 * GET  /api/v1/user/list            用户列表
 * GET  /api/v1/user/info            用户详情
 * POST /api/v1/user/save            新增 / 修改用户
 * POST /api/v1/user/remove          删除用户
 * POST /api/v1/user/setenable       启用 / 停用
 * POST /api/v1/user/resetpassword   重置密码
 * POST /api/v1/user/setpassword     修改自己的密码
 * </pre>
 *
 * <p>密码存储：{@code BCrypt(MD5(明文))}，与登录逻辑一致。
 * 本类不使用 {@code GbUser.password} 出参，避免哈希泄露。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RoleService roleService;
    private final GbUserMapper userMapper;

    /** 用户列表。 */
    @GetMapping("/list")
    public ApiResponse<UserListVo> list(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q) {
        StpUtil.checkPermission(Permissions.USER_READ);
        QueryWrapper<GbUser> wrapper = new QueryWrapper<>();
        if (q != null && !q.isBlank()) {
            String needle = q.trim();
            wrapper.and(w -> w.like("username", needle).or().like("nickname", needle));
        }
        wrapper.orderByAsc("id");
        List<GbUser> all = userMapper.selectList(wrapper);
        List<UserVo> list = new ArrayList<>();
        for (GbUser u : all) {
            list.add(userView(u));
        }
        UserListVo vo = new UserListVo();
        vo.setUserCount(list.size());
        vo.setUserList(PageQuery.slice(list, start, limit));
        return ApiResponse.ok(vo);
    }

    /** 用户详情。 */
    @GetMapping("/info")
    public ApiResponse<UserVo> info(@RequestParam(required = false) Long id) {
        StpUtil.checkLogin();
        GbUser user = id == null
                ? userService.findById(StpUtil.getLoginIdAsLong())
                : userService.findById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        // 非管理员只能看自己
        if (id != null && !id.equals(StpUtil.getLoginIdAsLong())) {
            StpUtil.checkPermission(Permissions.USER_READ);
        }
        return ApiResponse.ok(userView(user));
    }

    /** 新增 / 修改用户。 */
    @PostMapping("/save")
    public ApiResponse<IdVo> save(@RequestParam String username,
                                  @RequestParam(required = false) String password,
                                  @RequestParam(required = false) String nickname,
                                  @RequestParam(required = false) String role,
                                  @RequestParam(required = false) Long id,
                                  @RequestParam(required = false) Boolean enable) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        if (username.isBlank()) {
            throw BizException.badRequest("username 不能为空");
        }
        // 角色编码必须真实存在：写入一个不存在的编码，这个人会「什么权限都没有」
        // 且页面上查不出原因，排障成本极高。
        String normalizedRole = role == null ? null : role;
        if (normalizedRole != null && !normalizedRole.isBlank()) {
            normalizedRole = String.join(",",
                    roleService.requireExistingRoles(Permissions.splitRoles(normalizedRole)));
        }
        GbUser user;
        if (id == null) {
            if (userService.findByUsername(username) != null) {
                throw BizException.badRequest("用户名已存在：" + username);
            }
            if (password == null || password.isBlank()) {
                throw BizException.badRequest("新增用户必须提供 password");
            }
            user = new GbUser();
            user.setUsername(username);
            user.setPassword(userService.encodePassword(password));
            user.setNickname(nickname == null ? username : nickname);
            user.setRole(normalizedRole == null || normalizedRole.isBlank()
                    ? Permissions.ROLE_VIEWER : normalizedRole);
            user.setEnable(enable == null ? Boolean.TRUE : enable);
            userMapper.insert(user);
        } else {
            user = userService.findById(id);
            if (user == null) {
                throw BizException.notFound("用户不存在：" + id);
            }
            user.setUsername(username);
            user.setNickname(nickname == null ? user.getNickname() : nickname);
            if (normalizedRole != null && !normalizedRole.isBlank()) {
                user.setRole(normalizedRole);
            }
            if (enable != null) {
                user.setEnable(enable);
            }
            if (password != null && !password.isBlank()) {
                user.setPassword(userService.encodePassword(password));
            }
            userMapper.updateById(user);
        }
        return ApiResponse.ok(IdVo.ofUserId(user.getId()));
    }

    /** 删除用户。 */
    @PostMapping("/remove")
    public ApiResponse<DeletedVo> remove(@RequestParam String id) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        int deleted = 0;
        for (String token : id.split(",")) { // secret-scan: allow 这里 token 是逗号分隔的 ID 片段，不是凭据
            if (token.isBlank()) {
                continue;
            }
            long userId;
            try {
                userId = Long.parseLong(token.trim());
            } catch (NumberFormatException e) {
                throw BizException.badRequest("非法的用户 ID：" + token);
            }
            if (userId == StpUtil.getLoginIdAsLong()) {
                throw BizException.badRequest("不能删除当前登录用户");
            }
            deleted += userMapper.deleteById(userId);
            /*
             * 必须踢下线。
             *
             * <p>不踢的后果：鉴权只校验「登录态」，不校验账号是否还存在 ——
             * 被删账号的 token 能继续用到过期（默认 7 天）。
             * 想象一下「开除一个人、收回了账号，他还能看一周全部录像」的场景，
             * 这是审计一定会问的问题，也是真实的越权面。
             */
            kickoutQuietly(userId);
        }
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    /** 启用 / 停用。 */
    @PostMapping("/setenable")
    public ApiResponse<Void> setEnable(@RequestParam Long id,
                                       @RequestParam(defaultValue = "true") Boolean enable) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        if (id.equals(StpUtil.getLoginIdAsLong()) && !Boolean.TRUE.equals(enable)) {
            throw BizException.badRequest("不能停用当前登录用户");
        }
        GbUser user = userService.findById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在：" + id);
        }
        user.setEnable(enable);
        userMapper.updateById(user);
        // 停用必须立即生效：否则被停用的账号仍能用现有 token 继续操作到过期（默认 7 天）
        if (!Boolean.TRUE.equals(enable)) {
            kickoutQuietly(id);
        }
        return ApiResponse.ok();
    }

    /** 管理员重置密码。 */
    @PostMapping("/resetpassword")
    public ApiResponse<Void> resetPassword(@RequestParam Long id,
                                           @RequestParam String password) {
        StpUtil.checkPermission(Permissions.USER_WRITE);
        if (password == null || password.isBlank()) {
            throw BizException.badRequest("password 不能为空");
        }
        GbUser user = userService.findById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在：" + id);
        }
        user.setPassword(userService.encodePassword(password));
        userMapper.updateById(user);
        /*
         * 重置密码后把该用户所有会话踢下线。
         *
         * <p>重置密码的典型场景是「怀疑账号被盗」—— 若旧会话仍然有效，
         * 攻击者手里那个 token 还能继续用，改密码就失去了意义。
         */
        kickoutQuietly(id);
        log.info("管理员 {} 重置了用户 {} 的密码（已清除其全部会话）",
                StpUtil.getLoginId(), id);
        return ApiResponse.ok();
    }

    /** 修改自己的密码。 */
    @PostMapping("/setpassword")
    public ApiResponse<Void> setPassword(@RequestParam(value = "oldpassword") String oldPassword,
                                         @RequestParam(value = "newpassword") String newPassword) {
        StpUtil.checkLogin();
        userService.modifyPassword(StpUtil.getLoginIdAsLong(), oldPassword, newPassword);
        return ApiResponse.ok();
    }

    // ==================== 内部 ====================

    /**
     * 清除指定用户的全部会话（失败只记日志）。
     *
     * <p>为什么不让异常冒出去：踢下线是「附带动作」，主体操作（停用/删除/改密）
     * 已经落库成功了。如果因为 Sa-Token 的会话存储抖动而报错，
     * 前端会显示「操作失败」，管理员重试一次仍然失败 —— 而实际上账号已经是停用状态。
     * 这种「操作成功但提示失败」最容易被反复操作，反而把状态搞乱。
     * 所以这里降级为告警日志，并在日志里写清需要人工确认。
     */
    private void kickoutQuietly(long userId) {
        try {
            StpUtil.logout(userId);
        } catch (Exception e) {
            log.warn("清除用户 {} 的会话失败，该账号的旧 token 可能在过期前仍然有效（默认 7 天），"
                    + "请检查 sa_token_store 或手工清理：{}", userId, e.toString());
        }
    }

    /** 出参：不包含密码哈希。 */
    private UserVo userView(GbUser u) {
        UserVo vo = new UserVo();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setNickname(u.getNickname());
        vo.setRole(u.getRole());
        vo.setEnable(u.getEnable());
        vo.setPhoneNumber(u.getPhoneNumber());
        vo.setEmail(u.getEmail());
        vo.setDescription(u.getDescription());
        vo.setLock(u.getLock());
        vo.setHasAllChannel(u.getHasAllChannel());
        vo.setLastLoginAt(TimeFormats.toDisplay(u.getLastLoginAt()));
        vo.setCreatedAt(TimeFormats.toDisplay(u.getCreateTime()));
        return vo;
    }
}
