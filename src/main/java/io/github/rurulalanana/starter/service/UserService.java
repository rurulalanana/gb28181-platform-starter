package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
import io.github.rurulalanana.starter.vo.ApiKeyVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

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

    /** 密钥生成用：{@link java.util.UUID} 只有 122 位随机且格式可预测，不够。 */
    private static final SecureRandom RANDOM = new SecureRandom();

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

    // ==================== API 密钥 ====================

    /**
     * 给用户生成一把新的 API 密钥，<b>返回明文，仅此一次</b>。
     *
     * <p>库里只写 SHA-256 哈希 + 前 8 位 hint。重新生成即旧密钥立即失效
     * （哈希被覆盖，旧明文再也反查不到用户）。
     *
     * <p>用 {@link SecureRandom} 而不是 {@link java.util.UUID}：UUID v4 只有 122 位随机，
     * 且格式可预测；这里取 32 字节（256 位）再 base64url，抗穷举。
     */
    @Transactional
    public ApiKeyVo generateApiKey(Long userId) {
        GbUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        user.setApiKeyHash(hashApiKey(plain));
        user.setApiKeyHint(plain.substring(0, 8));
        user.setApiKeyCreatedAt(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);

        ApiKeyVo vo = new ApiKeyVo();
        vo.setKey(plain);
        vo.setHint(user.getApiKeyHint());
        vo.setEnabled(true);
        // 必须走 TimeFormats：LocalDateTime.toString() 会输出 2026-11-02T15:48:51.624609865
        // （T 分隔符 + 纳秒），前端直接展示会很难看，而且全仓其它时间字段都是 yyyy-MM-dd HH:mm:ss
        vo.setCreatedAt(TimeFormats.toDisplay(user.getApiKeyCreatedAt()));
        log.info("用户 {} 重新生成了 API 密钥（旧密钥立即失效）", user.getUsername());
        return vo;
    }

    /** 撤销 API 密钥：清空哈希后旧密钥立刻登不进来。 */
    @Transactional
    public boolean revokeApiKey(Long userId) {
        GbUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        boolean had = user.getApiKeyHash() != null;
        user.setApiKeyHash(null);
        user.setApiKeyHint(null);
        user.setApiKeyCreatedAt(null);
        user.setUpdateTime(LocalDateTime.now());
        // updateById 会跳过 null 字段（MyBatis-Plus 默认策略），这里必须显式置空，
        // 否则「撤销」看着成功、实际密钥仍然可用 —— 安全操作里这类静默失败最危险
        userMapper.update(null, Wrappers.<GbUser>lambdaUpdate()
                .eq(GbUser::getId, userId)
                .set(GbUser::getApiKeyHash, null)
                .set(GbUser::getApiKeyHint, null)
                .set(GbUser::getApiKeyCreatedAt, null)
                .set(GbUser::getUpdateTime, LocalDateTime.now()));
        if (had) {
            log.info("用户 {} 的 API 密钥已撤销", user.getUsername());
        }
        return had;
    }

    /** 查询密钥状态（不含明文）。 */
    public ApiKeyVo apiKeyStatus(Long userId) {
        GbUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        ApiKeyVo vo = new ApiKeyVo();
        vo.setEnabled(user.getApiKeyHash() != null);
        vo.setHint(user.getApiKeyHint());
        vo.setCreatedAt(TimeFormats.toDisplay(user.getApiKeyCreatedAt()));
        return vo;
    }

    /**
     * 凭密钥登录：按哈希反查用户，并做与密码登录一致的可用性校验。
     *
     * @return 匹配到的用户；密钥无效/账号不可用时返回 {@code null}
     */
    public GbUser authenticateByApiKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return null;
        }
        GbUser user = userMapper.selectOne(Wrappers.<GbUser>lambdaQuery()
                .eq(GbUser::getApiKeyHash, hashApiKey(plainKey.trim()))
                .last("limit 1"));
        if (user == null
                || Boolean.FALSE.equals(user.getEnable())
                || Boolean.TRUE.equals(user.getLock())) {
            return null;
        }
        return user;
    }

    /**
     * 密钥的存储形态：SHA-256 十六进制小写。
     *
     * <p>用 SHA-256 而不是 BCrypt：密钥是 256 位随机串，不存在字典攻击面；
     * 而登录时要在 SQL 里按哈希定位用户，必须能等值比较 —— BCrypt 每行盐不同，做不到。
     */
    public static String hashApiKey(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
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
