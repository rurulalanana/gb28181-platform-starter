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
 * 录像播放/下载地址的签名与校验。
 *
 * <p><b>为什么需要它</b>：标准部署里 {@code /record/**} 由 nginx（sendfile）
 * 直接读磁盘发出，<b>请求根本不经过 Spring</b>，所以 Sa-Token 拦截器对它毫无作用。
 * 结果是「知道路径就能下载」——而路径是可推断的：
 * {@code /record/<通道编码>/record/rtp/<流ID>/<日期>/<时间>-N.mp4}。
 * 实测过一条 60MB 的录像，不带任何凭据 {@code curl} 就能完整拉走。
 * 对一个部署在公网的平台来说，这等于全部录像对匿名用户开放。
 *
 * <p><b>做法</b>：服务端在回填 {@code playUrl} / {@code downloadUrl} 时，
 * 追加 {@code ?e=<过期秒>&t=<签名>}，签名为
 * {@code HMAC-SHA256(secret, "路径|过期秒")} 的十六进制前 32 位。
 * nginx 侧用 {@code auth_request} 把每个请求（含每次 Range 续传）转给
 * {@code /api/v1/record/auth} 校验。
 *
 * <p><b>为什么不直接把密钥写进 nginx 用 {@code secure_link}</b>：
 * 那样 nginx 与 Java 必须持有同一个密钥，两边不一致时<b>全部录像打不开</b>，
 * 而这类故障发生在部署环节、排查成本极高。走子请求校验则没有任何需要同步的配置。
 *
 * <p><b>为什么密钥是进程级随机而不是落库持久化</b>：
 * 落库意味着一次数据库泄露就永久丧失伪造防护；进程级密钥无法从存储中窃取。
 * 代价是服务重启后旧链接失效 —— 可以接受：链接本来就短命（默认 12 小时），
 * 且前端每次加载列表都会重新取址。
 *
 * <p>校验在 nginx 的鉴权子请求与本地静态资源映射（{@code record-static-enabled}）
 * 两处共用同一套逻辑，不会因为换部署形态而漏掉一侧。
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
     * 给录像地址追加签名参数。
     *
     * <p>非录像地址、或签名功能关闭时原样返回 —— 调用方不必判断，
     * 直接把返回值交给前端即可。
     *
     * @param url 形如 {@code /record/<...>.mp4} 的同源相对路径
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
     * 校验一个录像请求是否放行。
     *
     * <p>签名功能关闭时恒为 {@code true}：这样 nginx 侧可以<b>始终</b>
     * 配置鉴权子请求，开关完全由服务端决定，不需要改 nginx 或重启。
     *
     * @param requestUri 原始请求 URI（可含 query），即 nginx 传来的
     *                   {@code X-Original-URI} 或本进程的 {@code getRequestURI() + query}
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
