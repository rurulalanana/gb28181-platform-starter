package io.github.rurulalanana.starter.service;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import io.github.rurulalanana.starter.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防护：行为验证码 + 账号/IP 双维度失败计数 + 临时锁定。
 *
 * <h3>三层各挡什么</h3>
 * <ul>
 *   <li><b>验证码</b>挡自动化脚本（每次都要滑块交互，无法批量提交）；</li>
 *   <li><b>账号维度计数</b>挡对单个账号的定向爆破；</li>
 *   <li><b>IP 维度计数</b>挡「一个口令试一万个用户名」的撞库 —— 这种攻击在账号维度上永不超阈。</li>
 * </ul>
 *
 * <h3>验证码不能一刀切成「全都要」</h3>
 * 业务平台、脚本、Postman、接口文档的调试面板都是机器调用，<b>没法拖滑块</b>。
 * 所以登录接口另开一条路：带用户自己的 {@code X-Api-Key} 即免密码、免验证码
 * （密钥在「设置 → 账号安全」里由用户自行生成，每把绑一个账号、可随时撤销，见
 * {@code UserService#generateApiKey}）。
 * 无效密钥按 IP 计入失败，脚本狂试会被 IP 锁定。
 *
 * <h3>按账号锁定的副作用（刻意的取舍）</h3>
 * 攻击者可以故意锁死管理员账号。接受该风险的理由：锁定是<b>时间制</b>（默认 30 分钟）而非永久，
 * 重启进程即可清空内存计数。不锁的话，弱口令被撞开的概率远高于被恶意锁定的概率。
 *
 * <p>计数<b>只放内存</b>：落库会让每次登录多两次写，而锁定本就该随重启失效。
 * {@code gb_user.lock} 是人工封禁位，与这里的临时锁定两回事，绝不互相写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginGuardService {

    /** 失败计数窗口（分钟）：超窗口重新计数。 */
    @Value("${gb28181.login.fail-window-minutes:15}")
    private int failWindowMinutes;

    /** 账号维度失败几次锁定。 */
    @Value("${gb28181.login.lock-after-failures:10}")
    private int lockAfterFailures;

    /** IP 维度阈值更高：办公网常整层楼共用一个出口 IP。 */
    @Value("${gb28181.login.ip-lock-after-failures:50}")
    private int ipLockAfterFailures;

    /** 锁定时长（分钟）。 */
    @Value("${gb28181.login.lock-minutes:30}")
    private int lockMinutes;

    /**
     * 是否校验验证码。
     *
     * <p>只留这一个开关：之前还有一个「失败 N 次后才要验证码」，它意味着前 N 次裸奔 ——
     * 正好是爆破最需要的窗口，所以删了，开启即每次都验。
     *
     * <p>保留开关本身是因为验证码依赖图片生成，一旦它坏了（库、字体、资源任一环节），
     * 早上就遇过「谁都登不进去」。这种情况下把这里改成 false 重启即可应急。
     */
    @Value("${gb28181.login.captcha-enabled:true}")
    private boolean captchaEnabled;

    /** 验证通过后的 id 在服务端保留 2 分钟：够用户把密码打完，又不会让旧凭证长期可重放。 */
    private static final long CAPTCHA_PASS_TTL_MS = 120_000L;

    private final ImageCaptchaApplication captchaApplication;

    /** key = {@code u:用户名} / {@code i:IP}。 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 已通过轨迹校验、等待登录消费的验证码 id。
     *
     * <p><b>为什么自己存一份</b>：库的二次校验要注入 {@code SecondaryVerificationApplication}
     * 那个<b>具体类</b>（不在 {@code ImageCaptchaApplication} 接口上），还要开
     * {@code captcha.secondary.*}。自己维护一个集合更直接：语义就是「这个 id 刚通过过、
     * 且只能用一次」，不依赖插件的装配细节。
     *
     * <p>value 是过期时刻，过期后未消费就作废。
     */
    private final Map<String, Long> passed = new ConcurrentHashMap<>();

    /**
     * 生成验证码。
     *
     * @param type {@code SLIDER} / {@code ROTATE} / {@code CONCAT} / {@code WORD_IMAGE_CLICK} / {@code POW}
     */
    public ApiResponse<ImageCaptchaVO> generate(String type) {
        String t = (type == null || type.isBlank()) ? CaptchaTypeConstant.SLIDER : type;
        return captchaApplication.generateCaptcha(t);
    }

    /**
     * 校验滑动轨迹。
     *
     * <p>成功后把 id 记入待消费集合，登录时由 {@link #assertCaptchaPassed} 取用。
     *
     * @return 校验通过时的 captchaId；失败抛 400
     */
    public String checkTrack(String id, ImageCaptchaTrack track) {
        ApiResponse<?> result = captchaApplication.matching(id, track);
        if (result == null || !result.isSuccess()) {
            throw BizException.badRequest("验证码校验失败，请重新验证");
        }
        long now = System.currentTimeMillis();
        // 顺手清掉过期的，避免集合无界增长
        passed.entrySet().removeIf(e -> e.getValue() < now);
        passed.put(id, now + CAPTCHA_PASS_TTL_MS);
        return id;
    }

    /**
     * 登录前检查：账号/IP 是否处于临时锁定。
     *
     * @throws BizException 已锁定时 403，消息里带剩余分钟数
     */
    public void assertNotLocked(String username, String ip) {
        long now = System.currentTimeMillis();
        long left = Math.max(remaining(byUser(username), now), remaining(byIp(ip), now));
        if (left > 0) {
            long minutes = Math.max(1, (left + 59_999) / 60_000);
            throw BizException.forbidden("尝试次数过多，账号已临时锁定，请 " + minutes + " 分钟后再试");
        }
    }

    /** 本次登录是否必须携带验证码凭证（{@code false} 只可能是关了开关）。 */
    public boolean captchaRequired() {
        return captchaEnabled;
    }

    /**
     * 无效 API 密钥：只计 IP 维度。
     *
     * <p>不计账号维度 —— 密钥本身没对上任何一个账号，凭空造一个用户名桶会让计数表被垃圾填满。
     */
    public void onApiKeyFailure(String ip) {
        long now = System.currentTimeMillis();
        int ipFails = record(byIp(ip), now, ipLockAfterFailures);
        if (ipFails == ipLockAfterFailures) {
            log.warn("来源 IP {} 提交无效 API 密钥累计 {} 次，已临时锁定 {} 分钟",
                    ip, ipFails, lockMinutes);
        }
    }

    /**
     * 二次校验登录请求里的 captchaId，校验后立即作废。
     *
     * <p>只认 {@link #checkTrack} 刚刚登记过的 id —— 直接拿一个随机串打登录接口会在这一步被挡。
     */
    public void assertCaptchaPassed(String captchaId) {
        if (!captchaEnabled) {
            return;
        }
        if (captchaId == null || captchaId.isBlank()) {
            throw BizException.badRequest("请先完成验证码");
        }
        Long expireAt = passed.remove(captchaId);
        if (expireAt == null || expireAt < System.currentTimeMillis()) {
            throw BizException.badRequest("验证码已失效，请重新验证");
        }
    }

    /** 登录失败：两个维度各自累加，够阈值就锁定。 */
    public void onFailure(String username, String ip) {
        long now = System.currentTimeMillis();
        int userFails = record(byUser(username), now, lockAfterFailures);
        int ipFails = record(byIp(ip), now, ipLockAfterFailures);
        if (userFails == lockAfterFailures) {
            log.warn("账号 {} 连续登录失败 {} 次，已临时锁定 {} 分钟（来源 IP {}）",
                    username, userFails, lockMinutes, ip);
        }
        if (ipFails == ipLockAfterFailures) {
            log.warn("来源 IP {} 登录失败累计 {} 次，已临时锁定 {} 分钟", ip, ipFails, lockMinutes);
        }
    }

    /**
     * 登录成功：清掉该账号与 IP 的计数。
     *
     * <p>只清成功者自己的 key —— 否则攻击者用一个已知口令的账号不断登录，
     * 就能无限重置对 admin 的失败计数。
     */
    public void onSuccess(String username, String ip) {
        attempts.remove("u:" + normalize(username));
        attempts.remove("i:" + safeIp(ip));
    }

    /**
     * 解析来源 IP：优先 {@code X-Forwarded-For} 第一个地址。
     *
     * <p>前面挡着 nginx，{@code getRemoteAddr()} 只会是 127.0.0.1。
     */
    public static String clientIp(String xForwardedFor, String remoteAddr) {
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String first = xForwardedFor.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    // ==================== 内部 ====================

    private Attempt byUser(String username) {
        return attempts.computeIfAbsent("u:" + normalize(username), k -> new Attempt());
    }

    private Attempt byIp(String ip) {
        return attempts.computeIfAbsent("i:" + safeIp(ip), k -> new Attempt());
    }

    /** 用户名归一化：大小写不敏感，避免 Admin/admin 各算一套。 */
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static String safeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    /** 过了窗口期则归零，返回窗口内的有效失败次数。 */
    private int failures(Attempt a, long now) {
        if (a.windowStart > 0 && now - a.windowStart > failWindowMinutes * 60_000L) {
            a.reset();
        }
        return a.fails;
    }

    private long remaining(Attempt a, long now) {
        failures(a, now);
        return Math.max(0, a.lockedUntil - now);
    }

    /** 记一次失败，返回累计次数（达阈值同时设锁定时间）。 */
    private int record(Attempt a, long now, int lockAt) {
        if (a.windowStart == 0 || now - a.windowStart > failWindowMinutes * 60_000L) {
            a.reset();
            a.windowStart = now;
        }
        a.fails++;
        if (a.fails >= lockAt) {
            a.lockedUntil = now + lockMinutes * 60_000L;
        }
        return a.fails;
    }

    /** 单个维度（账号或 IP）的失败状态。 */
    private static final class Attempt {
        int fails;
        long windowStart;
        long lockedUntil;

        void reset() {
            fails = 0;
            windowStart = 0;
            lockedUntil = 0;
        }
    }
}
