package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 写操作权限守卫：把「谁能改什么」收口到一处。
 *
 * <p><b>为什么需要它</b>：改造前全平台 154 个接口里，除了用户管理与少数几个删除操作，
 * 其余<b>只校验「登录了」</b>。这意味着任何一个只读账号都能：
 * <ul>
 *   <li>改掉「设备统一接入密码」→ 全平台设备一起注册失败；</li>
 *   <li>调 {@code /system/restart?force=true} → 直接退出 JVM；</li>
 *   <li>删除设备、改通道云录开关、创建录像计划占满磁盘、改级联配置、控制云台。</li>
 * </ul>
 * 这不是「权限粒度不够细」，而是<b>权限模型不成立</b>：有一个能登录的账号就等于有全部权限。
 * 商用交付时这是第一个会被审计挑出来的问题，也是误操作导致生产事故的常见来源。
 *
 * <p><b>为什么用拦截器而不是在每个 Controller 上加注解</b>：
 * 逐个文件加注解要改 19 个类、154 个方法，漏一个就留一个洞，而且<b>以后新增接口默认仍是敞开的</b>。
 * 收口到拦截器后策略反转成「<b>默认拒绝</b>」：非 GET 请求一律要求写权限，
 * 新增接口不用记得加注解也不会漏。白名单显式列出「登录用户自己能做的事」，
 * 加错了最坏结果是「功能被挡」而不是「权限泄露」——失败方向是安全的。
 *
 * <p><b>角色模型</b>（{@code gb_user.role}，逗号分隔，取最高权限）：
 * <ul>
 *   <li>{@code admin} 管理员 —— 全部，含用户/系统配置/ACL/TLS/级联；</li>
 *   <li>{@code operator} 操作员 —— 日常运维：设备编辑、云台控制、录像、报警处理；
 *       不能改系统配置，不能管用户，不能动安全配置；</li>
 *   <li>其它（{@code user} / 未填）—— 只读：能看能播，不能改。</li>
 * </ul>
 * 三档是刻意的：绝大多数现场只需要「管理员配一次，操作员天天用，领导看画面」。
 * 更细的按通道授权见 {@code gb_user_channel}（本轮同时落地），
 * 但那是<b>数据可见范围</b>，与本类管的<b>操作能力</b>是两件事，不要混为一谈。
 */
@Slf4j
@Component
public class WritePermissionInterceptor implements HandlerInterceptor {

    /** 具备管理全部能力的角色。 */
    public static final String ROLE_ADMIN = "admin";
    /** 具备日常运维能力的角色。 */
    public static final String ROLE_OPERATOR = "operator";

    /**
     * 登录用户可自行调用的写接口（不需要写权限）。
     *
     * <p>只放「改自己的东西」：登录/登出/改自己密码/查自己信息。
     * <b>不要往这里加业务接口</b> —— 每加一个就等于对全部账号开放一次写能力。
     */
    private static final Set<String> SELF_SERVICE_PATHS = new HashSet<>(Arrays.asList(
            "/api/v1/login",
            "/api/v1/logout",
            "/api/v1/modifypassword",
            "/api/v1/user/setpassword"
    ));

    /** 前端为了「播放即点播」会 POST，但语义上只是取流地址，不改变任何持久状态。 */
    private static final Set<String> PLAYBACK_START_PATHS = new HashSet<>(Arrays.asList(
            "/api/v1/stream/start",
            "/api/v1/webrtc/play",
            // LivePlayer 内置 Webrtc tech 的 SRS 风格端点（裸 SDP 入 / base64 出）
            "/api/v1/webrtc/srs"
    ));

    /**
     * 仅管理员可调用的前缀。
     *
     * <p>判据是「这一下会不会影响别人 / 会不会让站点不可用」：
     * 系统配置、用户、安全（ACL/TLS）、级联（影响对端平台）、重启。
     */
    private static final List<String> ADMIN_ONLY_PREFIXES = Arrays.asList(
            "/api/v1/user",          // 用户与授权
            "/api/v1/config",        // 运行时配置（含 SIP 接入密码）
            "/api/v1/acl",           // 黑白名单
            "/api/v1/tls",           // 证书与 HTTPS
            "/api/v1/cascade",       // 国标级联（会影响对端平台的目录）
            "/api/v1/restart",       // 退出 JVM
            "/api/v1/oplog/clear",   // 清空审计（本身就是敏感操作）
            "/api/v1/system"         // 系统级
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        // GET/HEAD/OPTIONS 视为只读。查询接口不改变状态，允许只读账号用。
        // （唯一的例外是 POST 的 stream/start、webrtc/play，见下面单独判断）
        String method = request.getMethod();
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        if (!write) {
            return true;
        }
        // 未登录交给 Sa-Token 自己的拦截器处理（它会返回 401 语义），这里不重复判断
        if (!StpUtil.isLogin()) {
            return true;
        }
        if (SELF_SERVICE_PATHS.contains(path)) {
            return true;
        }
        Set<String> roles = rolesOfCurrentUser();

        // 播放取址：只读语义，任何登录用户都可以
        if (PLAYBACK_START_PATHS.contains(path)) {
            return true;
        }

        for (String prefix : ADMIN_ONLY_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                if (!roles.contains(ROLE_ADMIN)) {
                    log.warn("拒绝越权操作: operator={}, path={}, roles={}",
                            operatorName(), path, roles);
                    throw BizException.forbidden("该操作仅管理员可执行：" + path);
                }
                return true;
            }
        }

        // 其余写操作：管理员或操作员
        if (roles.contains(ROLE_ADMIN) || roles.contains(ROLE_OPERATOR)) {
            return true;
        }
        log.warn("拒绝只读账号的写操作: operator={}, path={}, roles={}",
                operatorName(), path, roles);
        throw BizException.forbidden("当前账号为只读权限，不能执行修改操作");
    }

    /** 当前登录用户的角色集合（小写）。 */
    private Set<String> rolesOfCurrentUser() {
        Set<String> out = new HashSet<>();
        try {
            for (String r : StpUtil.getRoleList()) {
                if (r != null) {
                    out.add(r.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            // 取不到角色 = 没有角色 = 只读，方向安全
            log.debug("读取当前用户角色失败：{}", e.toString());
        }
        return out;
    }

    private String operatorName() {
        try {
            return String.valueOf(StpUtil.getLoginIdDefaultNull());
        } catch (Exception e) {
            return "unknown";
        }
    }
}
