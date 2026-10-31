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
    /** 编辑设备：重命名、删除、媒体传输方式、目录/参数同步。 */
    public static final String DEVICE_WRITE = "device:write";
    /** 查看通道列表与通道详情。 */
    public static final String CHANNEL_READ = "channel:read";
    /** 编辑通道：名称、编号、云录开关、按需点播、音频。 */
    public static final String CHANNEL_WRITE = "channel:write";
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
    /** 管理云端录像：标记重要、录像计划、手动启停录像。 */
    public static final String CLOUD_WRITE = "cloud:write";
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
    /** 向上级转发云台等控制指令。 */
    public static final String CASCADE_CONTROL = "cascade:control";

    // ==================== 用户与角色 ====================

    /** 查看用户与角色。 */
    public static final String USER_READ = "user:read";
    /** 管理用户与角色（含重置他人密码）。 */
    public static final String USER_WRITE = "user:write";

    // ==================== 系统与安全 ====================

    /** 查看运行时配置、安全策略、TLS 状态、性能指标。 */
    public static final String SYSTEM_READ = "system:read";
    /** 修改运行时配置、黑白名单、证书、重启服务。 */
    public static final String SYSTEM_WRITE = "system:write";

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
            new Def(DEVICE_READ, "查看设备", "设备与通道", "设备列表、在线状态、目录树、参数日志"),
            new Def(DEVICE_WRITE, "编辑设备", "设备与通道", "重命名、删除、切换媒体传输方式、同步目录与参数"),
            new Def(CHANNEL_READ, "查看通道", "设备与通道", "通道列表与通道详情"),
            new Def(CHANNEL_WRITE, "编辑通道", "设备与通道", "改名称/编号、云端录像开关、按需点播、音频开关"),
            new Def(CHANNEL_PTZ, "云台控制", "设备与通道", "方向控制、预置位、巡航、拉框缩放"),

            new Def(PLAY_LIVE, "实时点播", "播放与录像", "打开实时画面、取流地址、停止播放"),
            new Def(PLAY_RECORD, "设备录像", "播放与录像", "录像检索、回放、下载"),

            new Def(CLOUD_READ, "查看云端录像", "云端录像", "时间轴、按日/月列表、分享"),
            new Def(CLOUD_WRITE, "管理云端录像", "云端录像", "标记重要、录像计划、手动启停录像"),
            new Def(CLOUD_DELETE, "删除云端录像", "云端录像", "删除录像文件（不可恢复）"),

            new Def(ALARM_READ, "查看报警", "报警", "报警记录列表"),
            new Def(ALARM_WRITE, "处理报警", "报警", "确认、忽略、删除单条报警"),
            new Def(ALARM_DELETE, "清空报警", "报警", "一键清空全部历史报警（不可恢复）"),

            new Def(CASCADE_READ, "查看级联", "级联", "上级/下级平台配置"),
            new Def(CASCADE_WRITE, "修改级联", "级联", "改上下级平台配置（影响对端目录）"),
            new Def(CASCADE_CONTROL, "级联控制", "级联", "向上级转发云台等控制指令"),

            new Def(USER_READ, "查看用户与角色", "用户与角色", "用户列表、角色列表"),
            new Def(USER_WRITE, "管理用户与角色", "用户与角色", "增删改用户、重置他人密码、增删改角色"),

            new Def(SYSTEM_READ, "查看系统配置", "系统与安全", "运行时配置、黑白名单、TLS 状态、性能指标"),
            new Def(SYSTEM_WRITE, "修改系统配置", "系统与安全", "改配置、改黑白名单、装证书、重启服务"),

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
     * 展开一组权限点。
     *
     * <p>含 {@link #ALL} 时返回<b>当前目录的全部编码</b>（而不是返回 {@code "*"}）——
     * 前端 {@code permissions.includes('device:write')} 这种写法才能直接生效，
     * 也不必让每个调用点都记得处理通配符。
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
            }
        }
        return out;
    }

    /** 全部「写」类权限点：{@link #expand} 之后判断「这个账号有没有任何改动能力」。 */
    public static Set<String> writeCodes() {
        Set<String> out = new LinkedHashSet<>();
        for (Def d : DEFS) {
            String c = d.code();
            if (c.endsWith(":write") || c.endsWith(":delete")
                    || c.endsWith(":clear") || c.endsWith(":ptz")) {
                out.add(c);
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
