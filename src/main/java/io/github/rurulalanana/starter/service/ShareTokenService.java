package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.entity.GbRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 录像分享令牌服务。
 *
 * <p>「分享页」= 生成一个免登录的链接，把某段录像分享给不登录平台的人看。
 * 对齐 LiveGBS 的「分享页」：详情页点分享 → 拿到链接 → 别人直接打开看。
 *
 * <p><b>为什么用签名而不是随机串存库</b>：分享链接是「给出去就长期有效」的东西，
 * 存库要多一张表、多一套增删改；而这里的链接只需要能定位到录像即可 ——
 * 用 HMAC 签名把「录像 ID + 过期时间」编码进 token，平台侧无状态即可校验，
 * 还能天然支持「设置有效期」。
 *
 * <p><b>安全边界</b>：签名只保证「这条链接确实由平台签发」，
 * 不做权限判断 —— 拿到链接的人就能看这一段录像。这正是「分享」的语义。
 * 故令牌有过期时间（默认 7 天），且只对<b>单段录像</b>生效，不是账号级授权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareTokenService {

    /** 分享链接默认有效期（天）。 */
    private static final int DEFAULT_TTL_DAYS = 7;

    /** 签名密钥：进程启动时随机生成，故重启后旧链接失效（可接受，且更安全）。 */
    private final byte[] secret = newSecret();

    private static byte[] newSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * 生成分享令牌。
     *
     * @param recordId 录像 ID
     * @return 令牌（URL 安全字符）
     */
    public String issue(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        long expire = System.currentTimeMillis() + DEFAULT_TTL_DAYS * 24L * 3600_000L;
        String payload = recordId + ":" + expire;
        String body = b64(payload);
        return body + "." + sign(body);
    }

    /**
     * 校验令牌并取出录像 ID。
     *
     * @param token 令牌
     * @return 录像 ID；无效或过期返回 {@code null}
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String body = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sign(body), sig)) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String recordId = payload.substring(0, colon);
        long expire;
        try {
            expire = Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (expire < System.currentTimeMillis()) {
            return null;
        }
        return recordId;
    }

    /** 按令牌取录像（令牌无效返回 null）。 */
    public GbRecord resolve(RecordService recordService, String token) {
        String recordId = verify(token);
        if (recordId == null) {
            return null;
        }
        return recordService.findRecord(recordId);
    }

    /**
     * HMAC-SHA256 签名（十六进制）。
     *
     * <p><b>注意用 {@link Mac} 而不是 {@link MessageDigest}</b>：
     * {@code MessageDigest.getInstance("HmacSHA256")} 会抛
     * {@code NoSuchAlgorithmException} —— HMAC 是「带密钥的摘要」，
     * JDK 把它放在 {@code javax.crypto.Mac} 里，不是 {@code MessageDigest}。
     * 之前写错导致每次生成分享链接都报「签名计算失败」。
     */
    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 定长比较，避免时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
