package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
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
    private final GbUserMapper userMapper;

    /** 用户列表。 */
    @GetMapping("/list")
    public ApiResponse<UserListVo> list(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
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
            StpUtil.checkRoleOr("admin");
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
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        if (username.isBlank()) {
            throw BizException.badRequest("username 不能为空");
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
            user.setRole(role == null ? "user" : role);
            user.setEnable(enable == null ? Boolean.TRUE : enable);
            userMapper.insert(user);
        } else {
            user = userService.findById(id);
            if (user == null) {
                throw BizException.notFound("用户不存在：" + id);
            }
            user.setUsername(username);
            user.setNickname(nickname == null ? user.getNickname() : nickname);
            if (role != null && !role.isBlank()) {
                user.setRole(role);
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
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        int deleted = 0;
        for (String token : id.split(",")) {
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
        }
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    /** 启用 / 停用。 */
    @PostMapping("/setenable")
    public ApiResponse<Void> setEnable(@RequestParam Long id,
                                       @RequestParam(defaultValue = "true") Boolean enable) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        if (id.equals(StpUtil.getLoginIdAsLong()) && !Boolean.TRUE.equals(enable)) {
            throw BizException.badRequest("不能停用当前登录用户");
        }
        GbUser user = userService.findById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在：" + id);
        }
        user.setEnable(enable);
        userMapper.updateById(user);
        return ApiResponse.ok();
    }

    /** 管理员重置密码。 */
    @PostMapping("/resetpassword")
    public ApiResponse<Void> resetPassword(@RequestParam Long id,
                                           @RequestParam String password) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        if (password == null || password.isBlank()) {
            throw BizException.badRequest("password 不能为空");
        }
        GbUser user = userService.findById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在：" + id);
        }
        user.setPassword(userService.encodePassword(password));
        userMapper.updateById(user);
        log.info("管理员 {} 重置了用户 {} 的密码", StpUtil.getLoginId(), id);
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
