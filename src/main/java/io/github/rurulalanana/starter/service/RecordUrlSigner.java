package io.github.rurulalanana.starter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 录像播放 / 下载地址的签名与校验。
 *
 * <p>必须做：{@code /record/**} 由 nginx sendfile 直读，<b>请求根本不经过 Spring</b>，Sa-Token
 * 拦截器毫无作用，而路径可推断（{@code /record/<通道>/record/rtp/<流ID>/<日期>/<时间>-N.mp4}）——
 * 实测一条 60MB 的录像不带凭据 {@code curl} 就能完整拉走。
 *
 * <p>做法：回填地址时追加 {@code ?e=<过期秒>&t=<签名>}，签名为 {@code HMAC-SHA256(secret, 路径|过期秒)}
 * 的前 32 位 hex；nginx 用 {@code auth_request} 把每个请求（含每次 Range 续传）转给
 * {@code /api/v1/record/auth} 校验。不用 nginx 的 {@code secure_link}：那要求两边持有同一密钥，
 * 不一致时全部录像打不开，而这类故障发生在部署环节。
 *
 * <p>密钥进程级随机不落库：落库意味着库一泄露就失去伪造防护；代价是重启后旧链接失效，
 * 而链接本来就短命（默认 12 小时）、列表每次加载都重新取址。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordUrlSigner {

    /** 签名覆盖的路径前缀：只对录像静态地址签名，其它 URL 原样返回。 */
    public static final String RECORD_PREFIX = "/record/";

    /** 参数名：过期时间（秒）、签名。短名是为了让地址不至于过长。 */
    private static final String PARAM_EXPIRE = "e";
    private static final String PARAM_SIGN = "t";

    /** HMAC 输出取前 32 个十六进制字符，128 位足够防伪造，地址也不会太丑。 */
    private static final int SIGN_HEX_LEN = 32;

    private final RuntimeConfigService config;

    /** 签名密钥：进程启动时随机生成，见类注释。 */
    private final byte[] secret = newSecret();

    private static byte[] newSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** 当前是否启用签名校验。 */
    public boolean enabled() {
        return config.getBoolean(ConfigKeys.RECORD_URL_SIGN_ENABLED);
    }

    /** 签名有效期（秒）。 */
    private long ttlSeconds() {
        return Math.max(300L, config.getInt(ConfigKeys.RECORD_URL_SIGN_TTL_SECONDS));
    }

    /**
     * 给录像地址追加签名参数。非录像地址、签名关闭时原样返回，调用方不必判断。
     *
     * @return 带 {@code ?e=..&t=..} 的地址
     */
    public String sign(String url) {
        if (url == null || url.isEmpty() || !url.startsWith(RECORD_PREFIX)) {
            return url;
        }
        if (!enabled()) {
            return url;
        }
        // 已有 query 就丢掉重签，避免同一地址出现两套 e/t 参数
        int q = url.indexOf('?');
        String path = q < 0 ? url : url.substring(0, q);
        long expire = System.currentTimeMillis() / 1000L + ttlSeconds();
        return path + "?" + PARAM_EXPIRE + "=" + expire
                + "&" + PARAM_SIGN + "=" + sign(path, expire);
    }

    /**
     * 校验一个录像请求是否放行。签名关闭时恒为 {@code true}：nginx 侧可始终配置鉴权子请求，
     * 开关完全由服务端决定，不需要改 nginx 或重启。
     *
     * @return 是否允许访问
     */
    public boolean allow(String requestUri) {
        if (!enabled()) {
            return true;
        }
        if (requestUri == null || requestUri.isEmpty()) {
            return false;
        }
        int q = requestUri.indexOf('?');
        String rawPath = q < 0 ? requestUri : requestUri.substring(0, q);
        if (!rawPath.startsWith(RECORD_PREFIX)) {
            // 不是录像路径：本校验器无权放行，也不该背锅
            return false;
        }
        if (q < 0) {
            return false;
        }
        String expireText = queryParam(requestUri.substring(q + 1), PARAM_EXPIRE);
        String given = queryParam(requestUri.substring(q + 1), PARAM_SIGN);
        if (expireText == null || given == null) {
            return false;
        }
        long expire;
        try {
            expire = Long.parseLong(expireText);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expire * 1000L < System.currentTimeMillis()) {
            log.debug("录像地址已过期: {}", rawPath);
            return false;
        }
        /*
         * 两侧都折算成「解码后的原始路径」再比签名。
         *
         * <p>签名侧用的是 toStaticUrl 产出的原始路径，请求侧是浏览器
         * 百分号编码后的形式（文件名含空格/中文时两者不同）。
         * 若直接比字符串，这类文件名会 100% 校验失败 —— 而且是
         * 「只有某些录像播不了」的诡异故障。
         */
        String canonical = percentDecode(rawPath);
        return constantTimeEquals(sign(canonical, expire), given);
    }

    // ==================== 内部 ====================

    private String sign(String path, long expire) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal((path + "|" + expire).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out).substring(0, SIGN_HEX_LEN);
        } catch (Exception e) {
            // HmacSHA256 是 JDK 必备算法，走到这里说明运行环境异常
            throw new IllegalStateException("录像地址签名失败", e);
        }
    }

    /** 取 query 中的参数值；不存在返回 {@code null}。 */
    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 路径百分号解码。
     *
     * <p>只处理 {@code %XX}，<b>不</b>把 {@code +} 当空格 ——
     * 那是表单/query 的规则，用在路径上会让文件名里的 {@code +} 变形，
     * 导致签名对不上。
     */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] buf = new byte[s.length()];
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf[len++] = (byte) ((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            buf[len++] = (byte) c;
        }
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    /** 定长比较，避免按字节提前返回泄露签名前缀。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }
}
