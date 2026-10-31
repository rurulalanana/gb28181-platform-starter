package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口级权限校验：把「哪个路径需要哪个权限点」收口到一张表里。
 *
 * <h3>为什么是拦截器而不是给每个方法加注解</h3>
 *
 * <p>逐个 Controller 加 {@code @SaCheckPermission} 要改 20 个类、150 多个方法，
 * 漏一个就留一个洞，而且<b>以后新增的接口默认仍是敞开的</b>。
 * 收口到拦截器后策略是「按前缀分类 + 未分类默认收紧」：
 * 新写的接口只要落在已分类的前缀下就自动受控，不用记得加注解。
 *
 * <h3>规则表怎么读</h3>
 *
 * <p>路径按<b>最长前缀</b>匹配（表内按长度降序排好，第一个命中的即为最长）。
 * 每条规则两个权限点：{@code read} 管 GET/HEAD/OPTIONS，{@code write} 管其余方法。
 * 少数接口「方法上是 POST、语义上是读取」（取流地址），在 {@link #WRITE_EXEMPT}
 * 里单独放行，避免让只读账号播不了视频。
 *
 * <h3>失败方向</h3>
 *
 * <p>没有匹配到规则的写请求不会直接放行，而是要求「具备任意一项写权限」并打 WARN 日志。
 * <b>宁可多拦一次（表现为功能被挡、日志有据可查），也不要少拦一次
 * （表现为越权成功而没人发现）</b>。
 *
 * <h3>与数据范围的关系</h3>
 *
 * <p>本类只判「能做什么」。{@code gb_user.has_all_channel} 目前<b>仍未生效</b>
 * —— 没有 {@code gb_user_channel} 表，设备/通道列表不做按用户过滤。
 * 有 {@code device:read} 的账号能看见全部设备，这是已知缺口。
 */
@Slf4j
@Component
public class WritePermissionInterceptor implements HandlerInterceptor {

    /** 一条路径规则：读方法要的权限点、写方法要的权限点。 */
    private record Rule(String read, String write) {
    }

    /**
     * 前缀规则，<b>顺序即优先级</b>（长的必须排在短的之前）。
     *
     * <p>例：{@code /api/v1/device/setchannel} 必须排在 {@code /api/v1/device} 之前，
     * 否则「改通道名称」会被当成「改设备」，只需要 {@code device:write} 就够了。
     */
    private static final Map<String, Rule> PREFIX_RULES = buildPrefixRules();

    private static Map<String, Rule> buildPrefixRules() {
        Map<String, Rule> m = new LinkedHashMap<>();

        // ---- 需要更细粒度、必须排在父前缀之前 ----
        m.put("/api/v1/device/setchannel", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_WRITE));
        m.put("/api/v1/stream/relay", new Rule(Permissions.PLAY_LIVE, Permissions.CASCADE_CONTROL));
        m.put("/api/v1/cloudrecord/plan", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_WRITE));
        // 删录像不可恢复：单列 cloud:delete，默认只给管理员
        m.put("/api/v1/cloudrecord/remove", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_DELETE));
        // 清空报警同理
        m.put("/api/v1/alarm/clear", new Rule(Permissions.ALARM_READ, Permissions.ALARM_DELETE));
        // 性能指标：能看设备的人就该看得到，不要求 system:read
        // （system:read 还覆盖运行时配置与 SIP 接入密码，不能顺手给只读账号）
        m.put("/api/v1/system/metrics", new Rule(Permissions.DEVICE_READ, Permissions.SYSTEM_WRITE));
        // 录像下载会在服务器上落文件：能看录像不等于能指挥服务器写盘，
        // 因此写动作要求 cloud:write（改造前也是 operator 以上才行）
        m.put("/api/v1/playback/download", new Rule(Permissions.PLAY_RECORD, Permissions.CLOUD_WRITE));

        // ---- 父前缀 ----
        m.put("/api/v1/device", new Rule(Permissions.DEVICE_READ, Permissions.DEVICE_WRITE));
        m.put("/api/v1/stream", new Rule(Permissions.PLAY_LIVE, Permissions.PLAY_LIVE));
        m.put("/api/v1/playback", new Rule(Permissions.PLAY_RECORD, Permissions.PLAY_RECORD));
        // 手动启停本地录像属于「写盘」，不是播放
        m.put("/api/v1/record", new Rule(Permissions.PLAY_RECORD, Permissions.CLOUD_WRITE));
        m.put("/api/v1/control", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ));
        // 解码/抓拍只取设备侧画面，与实时点播同等能力
        m.put("/api/v1/decode", new Rule(Permissions.PLAY_LIVE, Permissions.PLAY_LIVE));
        m.put("/api/v1/cloudrecord", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_WRITE));
        m.put("/api/v1/alarm", new Rule(Permissions.ALARM_READ, Permissions.ALARM_WRITE));
        m.put("/api/v1/cascade", new Rule(Permissions.CASCADE_READ, Permissions.CASCADE_WRITE));
        m.put("/api/v1/user", new Rule(Permissions.USER_READ, Permissions.USER_WRITE));
        m.put("/api/v1/role", new Rule(Permissions.USER_READ, Permissions.USER_WRITE));
        // 菜单外观：读对所有人开放（侧栏都要用，见 SELF_SERVICE_PATHS），
        // 改写全站导航 -> system:write
        m.put("/api/v1/menu", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/config", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/acl", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/tls", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/restart", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/system", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/oplog", new Rule(Permissions.OPLOG_READ, Permissions.OPLOG_CLEAR));
        return Map.copyOf(m);
    }

    /** 前缀清单，按长度降序缓存（避免每次请求都排序）。 */
    private static final List<String> PREFIXES_BY_LENGTH = sortedPrefixes();

    private static List<String> sortedPrefixes() {
        List<String> keys = new ArrayList<>(PREFIX_RULES.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        return keys;
    }

    /**
     * 登录用户可自行调用的接口，<b>不需要任何权限点</b>。
     *
     * <p>只放「改自己的东西 / 会话操作 / 每个人都要用的概览」。
     * <b>不要往这里加业务接口</b> —— 每加一个就等于对全部账号开放一次。
     */
    private static final Set<String> SELF_SERVICE_PATHS = new HashSet<>(List.of(
            "/api/v1/login",
            "/api/v1/logout",
            "/api/v1/modifypassword",
            // 改自己的密码：即便落在 /api/v1/user 前缀下，也不能要求 user:write
            "/api/v1/user/setpassword",
            // 当前登录用户信息与平台概览：每个登录用户都要用
            "/api/v1/userinfo",
            "/api/v1/getserverinfo",
            // 菜单外观配置：渲染侧栏要读，每个登录用户都要用；
            // 里面只有「菜单叫什么名字」，不含任何业务数据。写接口不经此白名单
            "/api/v1/menu/config"
    ));

    /**
     * 「方法上是 POST、语义上是读取」的接口。
     *
     * <p>前端为了「播放即点播」会 POST 取流地址，但它不改变任何持久状态
     * （SSRC/端口是会话级的，关流即回收）。
     * 若按写请求处理，只读账号就<b>永远播不了视频</b> —— 那是本末倒置。
     */
    private static final Set<String> WRITE_EXEMPT = new HashSet<>(List.of(
            "/api/v1/stream/start",
            "/api/v1/stream/stop",
            "/api/v1/playback/start",
            "/api/v1/playback/stop",
            "/api/v1/playback/control",
            "/api/v1/cloudrecord/play",
            "/api/v1/cloudrecord/stopplay"
    ));

    /** 收到「未分类的写请求」时用于告警去重，避免刷满日志。 */
    private final Set<String> unmappedWarned = ConcurrentHashMap.newKeySet();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler) {
        String path = normalize(request.getRequestURI());
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        // 未登录交给 Sa-Token 的登录拦截器处理（它给 401 语义），这里不重复判断。
        // 也必须这样放行：/api/v1/share/**、/api/v1/cloudrecord/snap/*/* 等
        // 豁免登录的路径，在图片/子请求里根本拿不到 token。
        if (!StpUtil.isLogin()) {
            return true;
        }
        if (SELF_SERVICE_PATHS.contains(path)) {
            return true;
        }

        boolean readOnlyMethod = "GET".equals(request.getMethod())
                || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod());
        boolean write = !readOnlyMethod && !WRITE_EXEMPT.contains(path);

        Rule rule = match(path);
        if (rule == null) {
            if (readOnlyMethod) {
                return true;
            }
            // 未分类的写请求：要求「具备任意一项写权限」。
            // 与改造前「admin 或 operator」等价（只读角色没有任何写权限点），
            // 对自定义角色也成立。
            if (hasAnyWritePermission()) {
                warnUnmapped(path);
                return true;
            }
            deny(path, "任意写权限", "当前账号没有任何修改权限，请让管理员为你分配角色");
            return false;
        }

        String required = write ? rule.write() : rule.read();
        if (required == null || required.isBlank()) {
            return true;
        }
        if (StpUtil.hasPermission(required)) {
            return true;
        }
        deny(path, required, write
                ? "没有「" + permName(required) + "」权限，无法执行该操作"
                : "没有「" + permName(required) + "」权限，无法查看该内容");
        return false;
    }

    /** 找匹配规则：最长前缀优先。 */
    private Rule match(String path) {
        for (String prefix : PREFIXES_BY_LENGTH) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return PREFIX_RULES.get(prefix);
            }
        }
        return null;
    }

    /** 当前账号是否具备任意一项写权限。 */
    private boolean hasAnyWritePermission() {
        for (String code : Permissions.writeCodes()) {
            if (StpUtil.hasPermission(code)) {
                return true;
            }
        }
        return false;
    }

    /** 统一拒绝出口：打日志 + 抛 403。 */
    private void deny(String path, String required, String message) {
        log.warn("拒绝越权请求: operator={}, path={}, required={}, perms={}",
                loginId(), path, required, currentPermissions());
        throw BizException.forbidden(message);
    }

    private void warnUnmapped(String path) {
        if (unmappedWarned.size() >= 200) {
            return;
        }
        if (unmappedWarned.add(path)) {
            log.warn("写请求未匹配权限规则，已按「具备任意写权限」放行：path={}。"
                    + "请在 WritePermissionInterceptor.PREFIX_RULES 里补一条规则", path);
        }
    }

    /** 权限点中文名（找不到时退回编码，方便排查）。 */
    private String permName(String code) {
        Permissions.Def def = Permissions.defOf(code);
        return def == null ? code : def.name();
    }

    /** 规整路径（去掉重复的 `/` 与结尾 `/`，防止规则漏匹配）。 */
    private String normalize(String uri) {
        if (uri == null) {
            return "";
        }
        String p = uri;
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String loginId() {
        try {
            return String.valueOf(StpUtil.getLoginIdDefaultNull());
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 当前账号权限快照，仅用于日志。 */
    private Set<String> currentPermissions() {
        try {
            return new LinkedHashSet<>(StpUtil.getPermissionList());
        } catch (Exception e) {
            return Set.of();
        }
    }
}
