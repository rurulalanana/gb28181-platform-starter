package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 用户与鉴权服务。
 *
 * <p>登录密码以 <b>MD5（32 位、小写、无中划线）</b>传输，服务端再用 BCrypt 校验存储值——即
 * <pre>存储值 = BCrypt(MD5(明文))</pre>
 * 这样既满足「前端传 MD5」的兼容性，又避免明文等价物直接落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final GbUserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 校验用户名密码。 */
    public GbUser authenticate(String username, String password) {
        if (username == null || username.isBlank()) {
            throw BizException.badRequest("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw BizException.badRequest("密码不能为空");
        }
        GbUser user = findByUsername(username);
        if (user == null) {
            throw BizException.badRequest("用户名或密码错误");
        }
        if (Boolean.FALSE.equals(user.getEnable())) {
            throw BizException.badRequest("账号已被禁用");
        }
        if (Boolean.TRUE.equals(user.getLock())) {
            throw BizException.badRequest("账号已被锁定");
        }
        if (!matches(password, user.getPassword())) {
            throw BizException.badRequest("用户名或密码错误");
        }
        return user;
    }

    /**
     * 校验密码。
     *
     * <p>兼容两种传参：前端传来的 32 位 MD5，或明文（调试 / 脚本场景）。
     * 存储值可能是 BCrypt（正常）或 MD5（历史数据），两者都接受。
     */
    private boolean matches(String raw, String stored) {
        if (stored == null) {
            return false;
        }
        String md5 = md5(raw);
        if (stored.startsWith("$2")) {
            return encoder.matches(md5, stored) || encoder.matches(raw, stored);
        }
        // 历史 MD5 存储
        return stored.equalsIgnoreCase(md5) || stored.equalsIgnoreCase(raw);
    }

    /** 记录登录时间。 */
    @Transactional
    public void touchLogin(Long userId) {
        GbUser user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /** 按用户名查询。 */
    public GbUser findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<GbUser>lambdaQuery()
                .eq(GbUser::getUsername, username).last("limit 1"));
    }

    /** 按 ID 查询。 */
    public GbUser findById(Long id) {
        return userMapper.selectById(id);
    }

    /** 修改密码。 */
    @Transactional
    public void modifyPassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw BizException.badRequest("新密码不能为空");
        }
        GbUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!matches(oldPassword, user.getPassword())) {
            throw BizException.badRequest("原密码错误");
        }
        user.setPassword(encoder.encode(md5(newPassword)));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /** 生成新密码的存储值（新增用户 / 重置密码用）。 */
    public String encodePassword(String raw) {
        return encoder.encode(md5(raw));
    }

    /** MD5 十六进制小写。 */
    public static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
