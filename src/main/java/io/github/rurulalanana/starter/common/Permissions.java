package io.github.rurulalanana.starter.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 权限点目录 —— 平台<b>唯一</b>的权限定义源。
 *
 * <h3>为什么要有这个类</h3>
 *
 * <p>改造前只有三个角色（admin / operator / user）写在字符串里，散落在
 * 7 个 Controller 的 17 处 {@code checkRoleOr("admin")}。这使得：
 * <ul>
 *   <li>想给「只看录像的管理员」这类岗位授权时，只能新增一个角色，
 *       但要改 Java 代码并重新发版；</li>
 *   <li>「能看设备」和「能改设备」在角色上不可分，只读账号到底能看到什么
 *       全靠拦截器里的路径前缀猜；</li>
 *   <li>前端不知道当前账号能做什么，只能猜 {@code role === 'admin'}，
 *       于是只读用户看得到所有「保存/删除」按钮，点下去才报 403。</li>
 * </ul>
 * 这里把「有哪些操作能力」固定成一份可枚举的清单，角色是这份清单的子集，
 * 由 {@code gb_role_permission} 落库，管理员在页面上自由组合。
 *
 * <h3>命名约定</h3>
 *
 * <p>{@code 资源:动作}，动作目前只有 {@code read} / {@code write}，例外是
 * {@code channel:ptz}（云台控制）与 {@code cascade:control}（向上级转发控制指令）——
 * 它们是<b>有副作用但不等同于配置修改</b>的能力，运维人员需要、而只读账号绝不该有。
 *
 * <h3>与数据范围的关系</h3>
 *
 * <p><b>本类只管「能做什么」，不管「能看到哪些通道」</b>。
 * {@code gb_user.has_all_channel} 目前仅在用户详情里回显，
 * 尚未按 {@code gb_user_channel} 过滤通道/设备查询 —— 也就是说
 * 一个有 {@code device:read} 的账号能看见全部设备。这是已知缺口，
 * 不要因为读到这里就以为数据范围已经隔离。真要做时请配套改
 * {@code DeviceService} 的列表查询，而不是在权限点上再切一刀。
 */
public final class Permissions {

    private Permissions() {
    }

    /** 通配权限：拥有它等于拥有 {@link #allCodes()} 里的全部权限点。 */
    public static final String ALL = "*";

    // ==================== 设备与通道 ====================

    /** 查看设备列表、状态、目录树、参数日志。 */
    public static final String DEVICE_READ = "device:read";
    /** 编辑设备：重命名、改 SIP 参数、切换媒体传输方式、下发设备密码。 */
    public static final String DEVICE_WRITE = "device:write";
    /** 修改设备基础信息（{@link #DEVICE_WRITE} 的按钮级细分）。 */
    public static final String DEVICE_UPDATE = "device:update";
    /** 采集/同步：拉目录、参数、状态、预置位，刷新大华 SDK。 */
    public static final String DEVICE_SYNC = "device:sync";
    /** 删除设备（不可恢复）。 */
    public static final String DEVICE_DELETE = "device:delete";

    /** 查看通道列表与通道详情。 */
    public static final String CHANNEL_READ = "channel:read";
    /** 编辑通道：名称、编号、云台类型、属性。 */
    public static final String CHANNEL_WRITE = "channel:write";
    /** 改通道名称/编号/类型（{@link #CHANNEL_WRITE} 的按钮级细分）。 */
    public static final String CHANNEL_UPDATE = "channel:update";
    /** 改通道经纬度。 */
    public static final String CHANNEL_POSITION = "channel:position";
    /** 切换按需直播。 */
    public static final String CHANNEL_ONDEMAND = "channel:ondemand";
    /** 切换云端录像开关。 */
    public static final String CHANNEL_RECORD = "channel:record";
    /** 切换音频开关。 */
    public static final String CHANNEL_AUDIO = "channel:audio";
    /** 设置通道是否共享给下级。 */
    public static final String CHANNEL_SHARE = "channel:share";
    /** 云台控制：方向、预置位、巡航、拉框缩放。 */
    public static final String CHANNEL_PTZ = "channel:ptz";

    // ==================== 播放与录像 ====================

    /** 实时点播（含取流地址、停止）。 */
    public static final String PLAY_LIVE = "play:live";
    /** 设备录像检索、回放与下载。 */
    public static final String PLAY_RECORD = "play:record";

    // ==================== 云端录像 ====================

    /** 查看云端录像（时间轴、月/日列表、分享）。 */
    public static final String CLOUD_READ = "cloud:read";
    /** 管理云端录像：标记重要、录像计划、手动启停录像、下载、分享。 */
    public static final String CLOUD_WRITE = "cloud:write";
    /** 标记/取消「重要」标记（{@link #CLOUD_WRITE} 的按钮级细分）。 */
    public static final String CLOUD_MARK = "cloud:mark";
    /** 生成分享链接。 */
    public static final String CLOUD_SHARE = "cloud:share";
    /** 维护录像计划。 */
    public static final String CLOUD_PLAN = "cloud:plan";
    /** 手动启停本地录像。 */
    public static final String CLOUD_RECORD = "cloud:record";
    /** 下载录像文件。 */
    public static final String CLOUD_DOWNLOAD = "cloud:download";
    /**
     * 删除云端录像文件。
     *
     * <p>单独拆出来是因为它<b>不可恢复</b>：{@code cloud:write} 里的
     * 「标记重要 / 改录像计划」都能改回来，而删掉的视频找不回来。
     * 默认只给管理员，现场若信任某类岗位可自行勾选。
     */
    public static final String CLOUD_DELETE = "cloud:delete";

    // ==================== 报警 ====================

    /** 查看报警记录。 */
    public static final String ALARM_READ = "alarm:read";
    /** 处理报警（确认 / 忽略 / 删除单条）。 */
    public static final String ALARM_WRITE = "alarm:write";
    /**
     * 清空全部报警记录。
     *
     * <p>与 {@link #ALARM_WRITE} 分开：单条处理是可追溯的日常动作，
     * 一键清空会丢掉全部历史警情，不可恢复。默认只给管理员。
     */
    public static final String ALARM_DELETE = "alarm:delete";

    // ==================== 级联 ====================

    /** 查看下级/上级平台配置。 */
    public static final String CASCADE_READ = "cascade:read";
    /** 修改级联配置（会影响对端平台的目录）。 */
    public static final String CASCADE_WRITE = "cascade:write";
    /** 选择共享给上级的通道（{@link #CASCADE_WRITE} 的按钮级细分）。 */
    public static final String CASCADE_SHARE = "cascade:share";
    /** 向上级推送目录。 */
    public static final String CASCADE_PUSH = "cascade:push";
    /** 删除级联配置。 */
    public static final String CASCADE_DELETE = "cascade:delete";
    /** 向上级转发云台等控制指令。 */
    public static final String CASCADE_CONTROL = "cascade:control";

    // ==================== 用户与角色 ====================

    /** 查看用户与角色。 */
    public static final String USER_READ = "user:read";
    /** 管理用户与角色（含重置他人密码）。 */
    public static final String USER_WRITE = "user:write";
    /** 重置他人密码（{@link #USER_WRITE} 的按钮级细分）。 */
    public static final String USER_RESET = "user:reset";
    /** 删除用户。 */
    public static final String USER_DELETE = "user:delete";
    /** 维护角色：新增/改名/改权限/启停/删除。 */
    public static final String ROLE_WRITE = "role:write";

    // ==================== 系统与安全 ====================

    /** 查看运行时配置、安全策略、TLS 状态、性能指标。 */
    public static final String SYSTEM_READ = "system:read";
    /** 修改系统：运行时配置、黑白名单、证书、重启、菜单外观。 */
    public static final String SYSTEM_WRITE = "system:write";
    /** 修改运行时配置（{@link #SYSTEM_WRITE} 的按钮级细分）。 */
    public static final String SYSTEM_CONFIG = "system:config";
    /** 维护 IP 黑白名单。 */
    public static final String SYSTEM_ACL = "system:acl";
    /** 安装/停用/重签 TLS 证书。 */
    public static final String SYSTEM_TLS = "system:tls";
    /** 重启服务。 */
    public static final String SYSTEM_RESTART = "system:restart";
    /** 维护菜单外观（名称/图标/分组/排序/隐藏）。 */
    public static final String MENU_WRITE = "menu:write";

    // ==================== 审计 ====================

    /** 查看操作日志。 */
    public static final String OPLOG_READ = "oplog:read";
    /** 清空操作日志（本身即为敏感操作）。 */
    public static final String OPLOG_CLEAR = "oplog:clear";

    /**
     * 权限点定义。
     *
     * @param code        权限点编码（{@code 资源:动作}）
     * @param name        中文名，直接用于页面勾选框
     * @param group       分组名，决定页面上的分组顺序
     * @param description 一句话说明，用于悬浮提示
     */
    public record Def(String code, String name, String group, String description) {
    }

    /**
     * 全部权限点，<b>顺序即页面展示顺序</b>。
     *
     * <p>新增权限点时只改这一处：角色页面自动出现勾选框，
     * {@link #allCodes()} 自动包含它，拥有 {@code *} 的管理员自动获得它。
     */
    private static final List<Def> DEFS = List.of(
            // ---- 设备：上级「完全控制」紧挨着它的下级动作，页面按声明顺序渲染 ----
            new Def(DEVICE_READ, "查看设备", "设备", "设备列表、在线状态、目录树、参数日志"),
            new Def(DEVICE_WRITE, "设备完全控制", "设备", "勾选后自动包含下面三项"),
            new Def(DEVICE_UPDATE, "编辑设备信息", "设备", "重命名、改 SIP 参数、切换媒体传输方式"),
            new Def(DEVICE_SYNC, "采集/同步设备", "设备", "拉取目录、参数、状态、预置位，刷新 SDK"),
            new Def(DEVICE_DELETE, "删除设备", "设备", "从平台移除设备（不可恢复）"),

            // ---- 通道 ----
            new Def(CHANNEL_READ, "查看通道", "通道", "通道列表与通道详情"),
            new Def(CHANNEL_WRITE, "通道完全控制", "通道", "勾选后自动包含下面六项"),
            new Def(CHANNEL_UPDATE, "改名/改编号", "通道", "改名称、国标编号、云台类型、属性"),
            new Def(CHANNEL_POSITION, "改经纬度", "通道", "设置通道经纬度"),
            new Def(CHANNEL_ONDEMAND, "按需直播开关", "通道", "切换通道按需点播"),
            new Def(CHANNEL_RECORD, "云端录像开关", "通道", "切换通道云端录像"),
            new Def(CHANNEL_AUDIO, "音频开关", "通道", "切换通道音频"),
            new Def(CHANNEL_SHARE, "共享通道", "通道", "设置通道是否共享给下级"),
            new Def(CHANNEL_PTZ, "云台控制", "通道", "方向控制、预置位、巡航、拉框缩放"),

            new Def(PLAY_LIVE, "实时点播", "播放与录像", "打开实时画面、取流地址、停止播放"),
            new Def(PLAY_RECORD, "设备录像", "播放与录像", "录像检索、回放、停止"),

            new Def(CLOUD_READ, "查看云端录像", "云端录像", "时间轴、按日/月列表"),
            new Def(CLOUD_WRITE, "云端录像完全控制", "云端录像", "勾选后自动包含下面五项"),
            new Def(CLOUD_MARK, "标记重要", "云端录像", "设置/取消重要标记"),
            new Def(CLOUD_SHARE, "分享录像", "云端录像", "生成分享链接与嵌入代码"),
            new Def(CLOUD_PLAN, "录像计划", "云端录像", "新增/修改/删除录像计划"),
            new Def(CLOUD_RECORD, "手动启停录像", "云端录像", "手动开始/停止本地录像"),
            new Def(CLOUD_DOWNLOAD, "下载录像", "云端录像", "下载设备录像与云端录像文件"),
            new Def(CLOUD_DELETE, "删除云端录像", "云端录像", "删除录像文件（不可恢复）"),

            new Def(ALARM_READ, "查看报警", "报警", "报警记录列表"),
            new Def(ALARM_WRITE, "处理报警", "报警", "确认、忽略、删除单条报警"),
            new Def(ALARM_DELETE, "清空报警", "报警", "一键清空全部历史报警（不可恢复）"),

            new Def(CASCADE_READ, "查看级联", "级联", "上级/下级平台配置"),
            new Def(CASCADE_WRITE, "级联完全控制", "级联", "勾选后自动包含下面三项"),
            new Def(CASCADE_SHARE, "选择共享通道", "级联", "勾选共享给上级的通道"),
            new Def(CASCADE_PUSH, "推送目录", "级联", "手动向上级推送目录"),
            new Def(CASCADE_DELETE, "删除级联", "级联", "删除上下级平台配置"),
            new Def(CASCADE_CONTROL, "级联控制", "级联", "向上级转发云台等控制指令"),

            new Def(USER_READ, "查看用户与角色", "用户与角色", "用户列表、角色列表"),
            new Def(USER_WRITE, "用户完全控制", "用户与角色", "勾选后自动包含下面三项"),
            new Def(USER_RESET, "重置密码", "用户与角色", "重置他人密码"),
            new Def(USER_DELETE, "删除用户", "用户与角色", "删除账号"),
            new Def(ROLE_WRITE, "维护角色", "用户与角色", "新增/改名/改权限/启停/删除角色"),

            new Def(SYSTEM_READ, "查看系统配置", "系统与安全", "运行时配置、黑白名单、TLS 状态、性能指标"),
            new Def(SYSTEM_WRITE, "系统完全控制", "系统与安全", "勾选后自动包含下面五项"),
            new Def(SYSTEM_CONFIG, "修改运行时配置", "系统与安全", "改配置项、恢复默认"),
            new Def(SYSTEM_ACL, "维护黑白名单", "系统与安全", "新增/删除 IP 访问规则"),
            new Def(SYSTEM_TLS, "维护证书", "系统与安全", "安装/停用/重签 TLS 证书"),
            new Def(SYSTEM_RESTART, "重启服务", "系统与安全", "重启平台进程"),
            new Def(MENU_WRITE, "维护菜单外观", "系统与安全", "改菜单名称/图标/分组/排序/隐藏"),

            new Def(OPLOG_READ, "查看操作日志", "审计", "操作日志列表"),
            new Def(OPLOG_CLEAR, "清空操作日志", "审计", "清空全部审计记录（敏感）")
    );

    /** 权限点 → 定义，便于按编码查中文名。 */
    private static final Map<String, Def> BY_CODE = buildIndex();

    private static Map<String, Def> buildIndex() {
        Map<String, Def> map = new LinkedHashMap<>();
        for (Def d : DEFS) {
            map.put(d.code(), d);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** 全部权限点定义（只读，按展示顺序）。 */
    public static List<Def> all() {
        return DEFS;
    }

    /** 全部权限点编码（不含 {@link #ALL}）。 */
    public static Set<String> allCodes() {
        Set<String> out = new LinkedHashSet<>();
        for (Def d : DEFS) {
            out.add(d.code());
        }
        return out;
    }

    /** 是否为已登记的权限点编码（{@link #ALL} 也算已知）。 */
    public static boolean isKnown(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim();
        return ALL.equals(c) || BY_CODE.containsKey(c);
    }

    /** 按编码取定义；未知编码返回 {@code null}。 */
    public static Def defOf(String code) {
        return code == null ? null : BY_CODE.get(code.trim());
    }

    /**
     * 「上级权限点 → 它包含的下级权限点」。
     *
     * <p>模块级编码（{@code x:write}）保留为<b>完全控制</b>语义：勾了它就等于
     * 勾了该模块下全部按钮级编码。这样改造前已有的角色不会因为拆细而丢权限，
     * 而只想给单个动作时可以直接勾下级编码、不勾上级。
     *
     * <p>刻意<b>不</b>按前缀自动推导：{@code channel:write} 若能推出
     * {@code channel:ptz}，那「能改通道名」的角色就顺带能转云台了，
     * 而云台是有副作用、需要单独授权的动作。
     */
    private static final Map<String, Set<String>> IMPLIED = Map.of(
            DEVICE_WRITE, Set.of(DEVICE_UPDATE, DEVICE_SYNC, DEVICE_DELETE),
            CHANNEL_WRITE, Set.of(CHANNEL_UPDATE, CHANNEL_POSITION, CHANNEL_ONDEMAND,
                    CHANNEL_RECORD, CHANNEL_AUDIO, CHANNEL_SHARE),
            CLOUD_WRITE, Set.of(CLOUD_MARK, CLOUD_SHARE, CLOUD_PLAN, CLOUD_RECORD, CLOUD_DOWNLOAD),
            CASCADE_WRITE, Set.of(CASCADE_SHARE, CASCADE_PUSH, CASCADE_DELETE),
            USER_WRITE, Set.of(USER_RESET, USER_DELETE, ROLE_WRITE),
            SYSTEM_WRITE, Set.of(SYSTEM_CONFIG, SYSTEM_ACL, SYSTEM_TLS, SYSTEM_RESTART, MENU_WRITE)
    );

    /** 该权限点被哪个上级编码包含；没有上级返回 {@code null}。 */
    public static String parentOf(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        for (Map.Entry<String, Set<String>> e : IMPLIED.entrySet()) {
            if (e.getValue().contains(c)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 该编码包含的下级编码（没有返回空集）。 */
    public static Set<String> childrenOf(String code) {
        if (code == null) {
            return Set.of();
        }
        return IMPLIED.getOrDefault(code.trim(), Set.of());
    }

    /**
     * 展开一组权限点。
     *
     * <p>含 {@link #ALL} 时返回<b>当前目录的全部编码</b>（而不是返回 {@code "*"}）——
     * 前端 {@code permissions.includes('device:write')} 这种写法才能直接生效，
     * 也不必让每个调用点都记得处理通配符。
     *
     * <p>{@code x:write} 会额外展开成它在 {@link #IMPLIED} 里声明的全部下级动作，
     * 因此「完全控制」对前端可见的也是具体按钮级编码。
     *
     * <p>未知编码会被<b>丢弃</b>：角色表里可能残留已下线的权限点
     * （例如删掉某个功能后），静默忽略比让它继续参与判断安全。
     */
    public static Set<String> expand(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String r : raw) {
            if (r == null) {
                continue;
            }
            String c = r.trim();
            if (ALL.equals(c)) {
                return allCodes();
            }
            if (BY_CODE.containsKey(c)) {
                out.add(c);
                out.addAll(IMPLIED.getOrDefault(c, Set.of()));
            }
        }
        return out;
    }

    /** 全部「写」类权限点：{@link #expand} 之后判断「这个账号有没有任何改动能力」。 */
    public static Set<String> writeCodes() {
        Set<String> out = new LinkedHashSet<>();
        for (Def d : DEFS) {
            // 「非读取」即改动类：新增按钮级编码时不用再回来补这里的后缀枚举
            if (!d.code().endsWith(":read")) {
                out.add(d.code());
            }
        }
        return out;
    }

    // ==================== 内置角色 ====================

    /** 管理员：全部权限。不可编辑、不可删除，保证永远不会把自己锁在门外。 */
    public static final String ROLE_ADMIN = "admin";
    /** 操作员：日常运维。 */
    public static final String ROLE_OPERATOR = "operator";
    /** 只读用户：能看能播，不能改。 */
    public static final String ROLE_VIEWER = "user";

    /** 内置角色编码 → 中文名。 */
    public static final Map<String, String> BUILTIN_ROLE_NAMES = Map.of(
            ROLE_ADMIN, "管理员",
            ROLE_OPERATOR, "操作员",
            ROLE_VIEWER, "只读用户"
    );

    /**
     * 内置角色的默认权限点。
     *
     * <p>只在第一次建库（或该角色在库里一条权限都没有）时写库，
     * 之后以库里的配置为准 —— 否则管理员在页面上改完，
     * 重启一次就被建库脚本覆盖回去了。
     */
    public static Map<String, Set<String>> builtinGrants() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put(ROLE_ADMIN, Set.of(ALL));

        map.put(ROLE_OPERATOR, new LinkedHashSet<>(List.of(
                DEVICE_READ, DEVICE_WRITE,
                CHANNEL_READ, CHANNEL_WRITE, CHANNEL_PTZ,
                PLAY_LIVE, PLAY_RECORD,
                CLOUD_READ, CLOUD_WRITE,
                ALARM_READ, ALARM_WRITE,
                CASCADE_READ, CASCADE_CONTROL,
                SYSTEM_READ, OPLOG_READ
        )));

        map.put(ROLE_VIEWER, new LinkedHashSet<>(List.of(
                DEVICE_READ, CHANNEL_READ,
                PLAY_LIVE, PLAY_RECORD,
                CLOUD_READ,
                ALARM_READ,
                CASCADE_READ
        )));
        return map;
    }

    /** 规范化角色编码（去空白、转小写）。 */
    public static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    /** 把逗号分隔的角色串拆成编码列表（去重、保序）。 */
    public static List<String> splitRoles(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String c = normalize(part);
            if (!c.isEmpty() && seen.add(c)) {
                out.add(c);
            }
        }
        return out;
    }
}
