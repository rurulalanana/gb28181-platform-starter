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
 * <p>不用逐方法 {@code @SaCheckPermission}：那要改 150 多个方法，漏一个就留一个洞，
 * 且新增接口默认仍是敞开的。路径按<b>最长前缀</b>匹配，每条规则两个权限点：
 * {@code read} 管 GET/HEAD/OPTIONS，{@code write} 管其余方法。
 * 少数接口「方法 POST、语义读取」（取流地址）在 {@link #WRITE_EXEMPT} 单独放行。
 *
 * <p><b>失败方向是默认拒绝</b>：未匹配规则的写请求要求「具备任意一项写权限」并打 WARN。
 * {@code gb_user.has_all_channel} 仍未生效，有 {@code device:read} 的账号能看见全部设备（已知缺口）。
 */
@Slf4j
@Component
public class WritePermissionInterceptor implements HandlerInterceptor {

    private record Rule(String read, String write) {
    }

    /**
     * 前缀规则，<b>顺序即优先级</b>（长的排在短的之前）。例：{@code /device/setchannel}
     * 必须排在 {@code /device} 之前，否则「改通道名称」会被当成「改设备」。
     */
    private static final Map<String, Rule> PREFIX_RULES = buildPrefixRules();

    private static Map<String, Rule> buildPrefixRules() {
        Map<String, Rule> m = new LinkedHashMap<>();

        // 新增按钮时先在这里补一行，再到前端把 canWrite 换成具体编码。
        m.put("/api/v1/device/remove", new Rule(Permissions.DEVICE_READ, Permissions.DEVICE_DELETE));
        putAll(m, new Rule(Permissions.DEVICE_READ, Permissions.DEVICE_SYNC),
                "/api/v1/device/fetchcatalog",
                "/api/v1/device/fetchinfo",
                "/api/v1/device/fetchstatus",
                "/api/v1/device/fetchconfig",
                "/api/v1/device/fetchpreset",
                "/api/v1/device/fetchhomeposition",
                "/api/v1/device/fetchcruisetracklist",
                "/api/v1/device/fetchcruisetrack",
                "/api/v1/device/fetchptzposition",
                "/api/v1/device/fetchsdcardstatus",
                "/api/v1/device/collect",
                "/api/v1/device/refreshsdk",
                "/api/v1/device/adddahua");
        putAll(m, new Rule(Permissions.DEVICE_READ, Permissions.DEVICE_WRITE),
                "/api/v1/device/setname",
                "/api/v1/device/setinfo",
                "/api/v1/device/setmediatransport",
                "/api/v1/device/setpassword");

        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_WRITE),
                "/api/v1/device/setchannelname",
                "/api/v1/device/setchannelid",
                "/api/v1/device/setchannelptztype",
                "/api/v1/device/setchannelproperty");
        m.put("/api/v1/device/setchannelposition", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_POSITION));
        m.put("/api/v1/device/setchannelondemand", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_ONDEMAND));
        m.put("/api/v1/device/setchannelcloudrecord", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_RECORD));
        m.put("/api/v1/device/setchannelaudio", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_AUDIO));
        m.put("/api/v1/device/setchannelshared", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_SHARE));
        // 兜底：未列出的 /setchannel* 仍按「编辑通道」处理
        m.put("/api/v1/device/setchannel", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_WRITE));

        // ---- 云端录像 ----
        m.put("/api/v1/cloudrecord/setimportant", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_MARK));
        m.put("/api/v1/cloudrecord/share", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_SHARE));
        m.put("/api/v1/cloudrecord/plan", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_PLAN));
        m.put("/api/v1/cloudrecord/download", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_DOWNLOAD));
        // 删录像不可恢复：单列 cloud:delete，默认只给管理员
        m.put("/api/v1/cloudrecord/remove", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_DELETE));

        putAll(m, new Rule(Permissions.CASCADE_READ, Permissions.CASCADE_SHARE),
                "/api/v1/cascade/savechannels",
                "/api/v1/cascade/removechannels",
                "/api/v1/cascade/sharenode");
        putAll(m, new Rule(Permissions.CASCADE_READ, Permissions.CASCADE_PUSH),
                "/api/v1/cascade/pushcatalog",
                "/api/v1/cascade/pushchannelcatalog");
        m.put("/api/v1/cascade/remove", new Rule(Permissions.CASCADE_READ, Permissions.CASCADE_DELETE));

        m.put("/api/v1/user/resetpassword", new Rule(Permissions.USER_READ, Permissions.USER_RESET));
        m.put("/api/v1/user/remove", new Rule(Permissions.USER_READ, Permissions.USER_DELETE));
        m.put("/api/v1/role", new Rule(Permissions.USER_READ, Permissions.ROLE_WRITE));

        m.put("/api/v1/config", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_CONFIG));
        m.put("/api/v1/acl", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_ACL));
        m.put("/api/v1/tls", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_TLS));
        m.put("/api/v1/restart", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_RESTART));
        m.put("/api/v1/menu", new Rule(Permissions.SYSTEM_READ, Permissions.MENU_WRITE));

        // ==================== 模块级规则 ====================
        m.put("/api/v1/stream/relay", new Rule(Permissions.PLAY_LIVE, Permissions.CASCADE_CONTROL));
        m.put("/api/v1/alarm/clear", new Rule(Permissions.ALARM_READ, Permissions.ALARM_DELETE));
        // 性能指标：能看设备的人就该看得到。不要求 system:read（它还覆盖运行时配置与
        // SIP 接入密码，不能顺手给只读账号）。
        m.put("/api/v1/system/metrics", new Rule(Permissions.DEVICE_READ, Permissions.SYSTEM_WRITE));
        // 录像下载会在服务器上落文件，单列 cloud:download
        m.put("/api/v1/playback/download", new Rule(Permissions.PLAY_RECORD, Permissions.CLOUD_DOWNLOAD));

        // ---- 云台控制：按面板 tab 拆成四组，四者平级不互为隐含 ----
        // 默认（/api/v1/control 前缀）仍要求 channel:ptz，下面的具体路径覆盖它。
        // 前缀清单按长度降序匹配，所以更长的路径优先命中。
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ),
                // 方向盘 + 变倍 + 光圈/聚焦 + 拉框缩放 + 中心喊话
                "/api/v1/control/ptz",
                "/api/v1/control/ptzprecise",
                "/api/v1/control/fi",
                "/api/v1/control/dragzoomin",
                "/api/v1/control/dragzoomout",
                "/api/v1/control/navigate",
                "/api/v1/control/talk");
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PRESET),
                // 预置位 tab：调用/设置/删除，以及「回原点」这种按编号跳转的
                "/api/v1/control/preset",
                "/api/v1/control/homeposition");
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ_DEVICE),
                // 设备 tab：雨刷、补光、设备录像、布防/撤防、报警布防
                "/api/v1/control/wiper",
                "/api/v1/control/filllight",
                "/api/v1/control/record",
                "/api/v1/control/guard",
                "/api/v1/control/alarmnotify",
                "/api/v1/control/alarmreset");
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ_ADVANCED),
                // 高级 tab：关键帧、重启、格式化、升级、校时、订阅、OSD/抓拍配置、目标跟踪
                "/api/v1/control/iframe",
                "/api/v1/control/teleboot",
                "/api/v1/control/formatsdcard",
                "/api/v1/control/deviceupgrade",
                "/api/v1/control/synctime",
                "/api/v1/control/deviceconfig",
                "/api/v1/control/devicesubscribe",
                "/api/v1/control/osdconfig",
                "/api/v1/control/snapshotconfig",
                "/api/v1/control/targettrack");

        // ---- 父前缀 ----
        m.put("/api/v1/device", new Rule(Permissions.DEVICE_READ, Permissions.DEVICE_WRITE));
        /*
         * 大华扩展能力。必须给出显式规则，否则会落到「未分类的写请求」被拒。
         * 权限点按功能性质分：
         *   云台扩展（巡航/巡迹/限位/重启）→ channel:ptz
         *   相机参数                        → channel:ptz:device（与「设备 tab」同级）
         *   对讲                            → channel:ptz（喊话本来就是云台面板的入口）
         *   录像下载                        → channel:record
         */
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ),
                "/api/v1/dahua/ptz",
                "/api/v1/dahua/talk");
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ_DEVICE),
                "/api/v1/dahua/cameraconfig");
        // 录像计划读写与录像下载同属「录像」能力域
        putAll(m, new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_RECORD),
                "/api/v1/dahua/record/download",
                "/api/v1/dahua/record/plan");
        m.put("/api/v1/stream", new Rule(Permissions.PLAY_LIVE, Permissions.PLAY_LIVE));
        m.put("/api/v1/playback", new Rule(Permissions.PLAY_RECORD, Permissions.PLAY_RECORD));
        // 手动启停本地录像属于「写盘」，不是播放
        m.put("/api/v1/record", new Rule(Permissions.PLAY_RECORD, Permissions.CLOUD_RECORD));
        m.put("/api/v1/control", new Rule(Permissions.CHANNEL_READ, Permissions.CHANNEL_PTZ));
        // 解码/抓拍只取设备侧画面，与实时点播同等能力
        m.put("/api/v1/decode", new Rule(Permissions.PLAY_LIVE, Permissions.PLAY_LIVE));
        m.put("/api/v1/cloudrecord", new Rule(Permissions.CLOUD_READ, Permissions.CLOUD_WRITE));
        m.put("/api/v1/alarm", new Rule(Permissions.ALARM_READ, Permissions.ALARM_WRITE));
        m.put("/api/v1/cascade", new Rule(Permissions.CASCADE_READ, Permissions.CASCADE_WRITE));
        m.put("/api/v1/user", new Rule(Permissions.USER_READ, Permissions.USER_WRITE));
        m.put("/api/v1/system", new Rule(Permissions.SYSTEM_READ, Permissions.SYSTEM_WRITE));
        m.put("/api/v1/oplog", new Rule(Permissions.OPLOG_READ, Permissions.OPLOG_CLEAR));
        return Map.copyOf(m);
    }

    /** 一次登记多条路径到同一条规则（按钮级路径大多一个动作一个前缀）。 */
    private static void putAll(Map<String, Rule> m, Rule rule, String... paths) {
        for (String p : paths) {
            m.put(p, rule);
        }
    }

    /** 前缀清单，按长度降序缓存（避免每次请求都排序）。 */
    private static final List<String> PREFIXES_BY_LENGTH = sortedPrefixes();

    private static List<String> sortedPrefixes() {
        List<String> keys = new ArrayList<>(PREFIX_RULES.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        return keys;
    }

    /**
     * 登录用户可自行调用的接口，<b>不需要任何权限点</b>。只放「改自己的东西 / 会话操作 /
     * 每个人都要用的概览」。<b>不要往这里加业务接口</b> —— 每加一个就等于对全部账号开放一次。
     */
    private static final Set<String> SELF_SERVICE_PATHS = new HashSet<>(List.of(
            "/api/v1/login",
            "/api/v1/logout",
            "/api/v1/modifypassword",
            // 改自己的密码：即便落在 /api/v1/user 前缀下，也不能要求 user:write
            "/api/v1/user/setpassword",
            // 自己的 API 密钥：同属「改自己的东西」。三个路径都要列 ——
            // 下面的匹配是精确 contains，不是前缀匹配，写 "/api/v1/user/apikey" 盖不住子路径。
            // 三个接口内部都只取 StpUtil.getLoginId()，操作不到别人的密钥。
            "/api/v1/user/apikey",
            "/api/v1/user/apikey/generate",
            "/api/v1/user/apikey/revoke",
            "/api/v1/userinfo",
            "/api/v1/getserverinfo",
            // 菜单外观：渲染侧栏要读；只有菜单名称，不含业务数据。写接口不经此白名单
            "/api/v1/menu/config",
            // 菜单结构补齐：幂等。要求管理权限的话，普通账号首开会被「菜单还没种」困扰
            "/api/v1/menu/sync"
    ));

    /**
     * 「方法上是 POST、语义上是读取」的接口。前端为了「播放即点播」会 POST 取流地址，
     * 它不改变持久状态（SSRC/端口是会话级的，关流即回收）。按写请求处理会让只读账号永远播不了视频。
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
        // 未登录交给 Sa-Token 处理（401）；分享页/快照等免登录路径在图片/子请求里拿不到 token。
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
            // 未分类的写请求：要求「具备任意一项写权限」。与改造前「admin 或 operator」等价
            // （只读角色没有任何写权限点），对自定义角色也成立。
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
