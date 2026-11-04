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
 * 权限点目录 —— 平台<b>唯一</b>的权限定义源。角色是这份清单的子集，存 {@code gb_role_permission}，
 * 管理员在页面上自由组合；前端按权限点控制按钮，而不是猜 {@code role == 'admin'}。
 *
 * <p>命名 {@code 资源:动作}，动作通常只有 read/write。云台按控制面板的 tab 拆成四个点：
 * {@code channel:ptz}（方向盘/变倍/光圈聚焦）、{@code channel:preset}（预置位）、
 * {@code channel:ptz:device}（雨刷补光录像布防）、{@code channel:ptz:advanced}（重启/格式化等）。
 * 四者是<b>平级</b>的，{@code channel:ptz} 不隐含其余三个 —— 想让人只能转方向、不能重启设备，
 * 就必须能单独授权。
 *
 * <p><b>本类只管「能做什么」，不管「能看到哪些通道」</b>。{@code gb_user.has_all_channel} 目前仅在
 * 用户详情回显，尚未按 {@code gb_user_channel} 过滤查询：有 {@code device:read} 的账号能看见全部设备。
 * 这是已知缺口，真要做时请改 {@code DeviceService} 的列表查询，不要在权限点上再切一刀。
 */
public final class Permissions {

    private Permissions() {
    }

    /** 通配权限：拥有它等于拥有 {@link #allCodes()} 里的全部权限点。 */
    public static final String ALL = "*";

    // ==================== 设备与通道 ====================

    public static final String DEVICE_READ = "device:read";
    /** 设备完全控制：隐含下面的更新/同步/删除。 */
    public static final String DEVICE_WRITE = "device:write";
    public static final String DEVICE_UPDATE = "device:update";
    public static final String DEVICE_SYNC = "device:sync";
    /** 删除设备（不可恢复）。 */
    public static final String DEVICE_DELETE = "device:delete";

    public static final String CHANNEL_READ = "channel:read";
    /** 通道完全控制：隐含下面的改名/经纬度/按需/录像/音频/共享。 */
    public static final String CHANNEL_WRITE = "channel:write";
    public static final String CHANNEL_UPDATE = "channel:update";
    public static final String CHANNEL_POSITION = "channel:position";
    public static final String CHANNEL_ONDEMAND = "channel:ondemand";
    public static final String CHANNEL_RECORD = "channel:record";
    public static final String CHANNEL_AUDIO = "channel:audio";
    public static final String CHANNEL_SHARE = "channel:share";
    /** 普通云台：方向盘、变倍、光圈/聚焦、拉框缩放、喊话。 */
    public static final String CHANNEL_PTZ = "channel:ptz";
    /** 预置位 tab：调用 / 设置 / 删除。 */
    public static final String CHANNEL_PRESET = "channel:preset";
    /** 设备功能 tab：雨刷、补光、设备录像、布防/撤防。 */
    public static final String CHANNEL_PTZ_DEVICE = "channel:ptz:device";
    /** 高级操作 tab：强制关键帧、远程重启、格式化存储卡、升级、OSD/抓拍配置等。 */
    public static final String CHANNEL_PTZ_ADVANCED = "channel:ptz:advanced";

    // ==================== 播放与录像 ====================

    public static final String PLAY_LIVE = "play:live";
    public static final String PLAY_RECORD = "play:record";

    // ==================== 云端录像 ====================

    public static final String CLOUD_READ = "cloud:read";
    /** 云端录像完全控制：隐含下面的标记/分享/计划/启停/下载。 */
    public static final String CLOUD_WRITE = "cloud:write";
    public static final String CLOUD_MARK = "cloud:mark";
    public static final String CLOUD_SHARE = "cloud:share";
    public static final String CLOUD_PLAN = "cloud:plan";
    public static final String CLOUD_RECORD = "cloud:record";
    public static final String CLOUD_DOWNLOAD = "cloud:download";
    /**
     * 删除云端录像文件。单独拆出是因为<b>不可恢复</b>：{@code cloud:write} 里的标记/计划都能改回来，
     * 删掉的视频找不回来。默认只给管理员。
     */
    public static final String CLOUD_DELETE = "cloud:delete";

    // ==================== 报警 ====================

    public static final String ALARM_READ = "alarm:read";
    public static final String ALARM_WRITE = "alarm:write";
    /**
     * 清空全部报警记录。与 {@link #ALARM_WRITE} 分开：单条处理可追溯，
     * 一键清空会丢掉全部历史警情且不可恢复。默认只给管理员。
     */
    public static final String ALARM_DELETE = "alarm:delete";

    // ==================== 级联 ====================

    public static final String CASCADE_READ = "cascade:read";
    /** 修改级联配置（会影响对端平台的目录）。 */
    public static final String CASCADE_WRITE = "cascade:write";
    public static final String CASCADE_SHARE = "cascade:share";
    public static final String CASCADE_PUSH = "cascade:push";
    public static final String CASCADE_DELETE = "cascade:delete";
    /** 向上级转发云台等控制指令。 */
    public static final String CASCADE_CONTROL = "cascade:control";

    // ==================== 用户与角色 ====================

    public static final String USER_READ = "user:read";
    /** 管理用户与角色（含重置他人密码）。 */
    public static final String USER_WRITE = "user:write";
    public static final String USER_RESET = "user:reset";
    public static final String USER_DELETE = "user:delete";
    public static final String ROLE_WRITE = "role:write";

    // ==================== 系统与安全 ====================

    public static final String SYSTEM_READ = "system:read";
    /** 系统完全控制：隐含配置/黑白名单/证书/重启/菜单。 */
    public static final String SYSTEM_WRITE = "system:write";
    public static final String SYSTEM_CONFIG = "system:config";
    public static final String SYSTEM_ACL = "system:acl";
    public static final String SYSTEM_TLS = "system:tls";
    public static final String SYSTEM_RESTART = "system:restart";
    public static final String MENU_WRITE = "menu:write";

    // ==================== 审计 ====================

    public static final String OPLOG_READ = "oplog:read";
    /** 清空操作日志（本身即为敏感操作）。 */
    public static final String OPLOG_CLEAR = "oplog:clear";

    /**
     * 权限点定义。
     *
     * <p>三级结构，与角色编辑页的树形表格一一对应：{@code group} 是顶级目录（设备与通道），
     * {@code module} 是二级菜单（通道控制），{@code name} 是挂在菜单上的按钮权限。
     * 同一 module 的按钮在页面右侧排成一行网格。
     */
    public record Def(String code, String name, String module, String group, String description) {
    }

    /** 全部权限点，<b>顺序即页面展示顺序</b>。新增权限点只改这一处：角色页自动出现勾选框，
     *  {@link #allCodes()} 与 {@code *} 自动包含。 */
    private static final List<Def> DEFS = List.of(
            // ==================== 设备与通道 ====================
            new Def(DEVICE_READ, "查看设备", "设备列表", "设备与通道", "设备列表、在线状态、目录树、参数日志"),
            new Def(DEVICE_WRITE, "设备完全控制", "设备列表", "设备与通道", "勾选后自动包含下面三项"),
            new Def(DEVICE_UPDATE, "编辑设备信息", "设备列表", "设备与通道", "重命名、改 SIP 参数、切换媒体传输方式"),
            new Def(DEVICE_SYNC, "采集/同步", "设备列表", "设备与通道", "拉取目录、参数、状态、预置位，刷新 SDK"),
            new Def(DEVICE_DELETE, "删除设备", "设备列表", "设备与通道", "从平台移除设备（不可恢复）"),

            new Def(CHANNEL_READ, "查看通道", "通道列表", "设备与通道", "通道列表与通道详情"),
            new Def(CHANNEL_WRITE, "通道完全控制", "通道列表", "设备与通道", "勾选后自动包含下面六项"),
            new Def(CHANNEL_UPDATE, "改名/改编号", "通道列表", "设备与通道", "改名称、国标编号、云台类型、属性"),
            new Def(CHANNEL_POSITION, "改经纬度", "通道列表", "设备与通道", "设置通道经纬度"),
            new Def(CHANNEL_ONDEMAND, "按需直播开关", "通道列表", "设备与通道", "切换通道按需点播"),
            new Def(CHANNEL_RECORD, "云端录像开关", "通道列表", "设备与通道", "切换通道云端录像"),
            new Def(CHANNEL_AUDIO, "音频开关", "通道列表", "设备与通道", "切换通道音频"),
            new Def(CHANNEL_SHARE, "共享通道", "通道列表", "设备与通道", "设置通道是否共享给下级"),

            new Def(CHANNEL_PTZ, "普通云台", "通道控制", "设备与通道",
                    "方向盘、变倍、光圈/聚焦、拉框缩放、中心喊话"),
            new Def(CHANNEL_PRESET, "预置位", "通道控制", "设备与通道", "预置位 tab：调用、设置、删除"),
            new Def(CHANNEL_PTZ_DEVICE, "设备功能", "通道控制", "设备与通道",
                    "设备 tab：雨刷、补光、设备录像、布防/撤防"),
            new Def(CHANNEL_PTZ_ADVANCED, "高级操作", "通道控制", "设备与通道",
                    "高级 tab：强制关键帧、远程重启、格式化存储卡、升级、OSD/抓拍配置等"),

            // ==================== 播放与录像 ====================
            new Def(PLAY_LIVE, "实时点播", "实时预览", "播放与录像", "打开实时画面、取流地址、停止播放"),
            new Def(PLAY_RECORD, "设备录像", "录像回放", "播放与录像", "录像检索、回放、停止"),

            // ==================== 云端录像 ====================
            new Def(CLOUD_READ, "查看云端录像", "录像文件", "云端录像", "时间轴、按日/月列表"),
            new Def(CLOUD_WRITE, "云端录像完全控制", "录像文件", "云端录像", "勾选后自动包含下面四项"),
            new Def(CLOUD_MARK, "标记重要", "录像文件", "云端录像", "设置/取消重要标记"),
            new Def(CLOUD_SHARE, "分享录像", "录像文件", "云端录像", "生成分享链接与嵌入代码"),
            new Def(CLOUD_DOWNLOAD, "下载录像", "录像文件", "云端录像", "下载设备录像与云端录像文件"),
            new Def(CLOUD_DELETE, "删除云端录像", "录像文件", "云端录像", "删除录像文件（不可恢复）"),
            new Def(CLOUD_PLAN, "录像计划", "录像计划", "云端录像", "新增/修改/删除录像计划"),
            new Def(CLOUD_RECORD, "手动启停录像", "录像控制", "云端录像", "手动开始/停止本地录像"),

            // ==================== 报警 ====================
            new Def(ALARM_READ, "查看报警", "报警记录", "报警", "报警记录列表"),
            new Def(ALARM_WRITE, "处理报警", "报警记录", "报警", "确认、忽略、删除单条报警"),
            new Def(ALARM_DELETE, "清空报警", "报警记录", "报警", "一键清空全部历史报警（不可恢复）"),

            // ==================== 级联 ====================
            new Def(CASCADE_READ, "查看级联", "级联平台", "级联", "上级/下级平台配置"),
            new Def(CASCADE_WRITE, "级联完全控制", "级联平台", "级联", "勾选后自动包含下面三项"),
            new Def(CASCADE_SHARE, "选择共享通道", "级联平台", "级联", "勾选共享给上级的通道"),
            new Def(CASCADE_PUSH, "推送目录", "级联平台", "级联", "手动向上级推送目录"),
            new Def(CASCADE_DELETE, "删除级联", "级联平台", "级联", "删除上下级平台配置"),
            new Def(CASCADE_CONTROL, "级联控制", "级联平台", "级联", "向上级转发云台等控制指令"),

            // ==================== 用户与角色 ====================
            new Def(USER_READ, "查看用户", "用户管理", "用户与角色", "用户列表"),
            new Def(USER_WRITE, "用户完全控制", "用户管理", "用户与角色", "勾选后自动包含下面三项"),
            new Def(USER_RESET, "重置密码", "用户管理", "用户与角色", "重置他人密码"),
            new Def(USER_DELETE, "删除用户", "用户管理", "用户与角色", "删除账号"),
            new Def(ROLE_WRITE, "维护角色", "角色管理", "用户与角色", "新增/改名/改权限/启停/删除角色"),

            // ==================== 系统与安全 ====================
            new Def(SYSTEM_READ, "查看系统配置", "运行时配置", "系统与安全", "配置项、黑白名单、TLS 状态、性能指标"),
            new Def(SYSTEM_WRITE, "系统完全控制", "运行时配置", "系统与安全", "勾选后自动包含下面五项"),
            new Def(SYSTEM_CONFIG, "修改运行时配置", "运行时配置", "系统与安全", "改配置项、恢复默认"),
            new Def(SYSTEM_ACL, "维护黑白名单", "访问控制", "系统与安全", "新增/删除 IP 访问规则"),
            new Def(SYSTEM_TLS, "维护证书", "证书管理", "系统与安全", "安装/停用/重签 TLS 证书"),
            new Def(MENU_WRITE, "维护菜单外观", "菜单外观", "系统与安全", "改菜单名称/图标/分组/排序/隐藏"),
            new Def(SYSTEM_RESTART, "重启服务", "服务运维", "系统与安全", "重启平台进程"),

            // ==================== 审计 ====================
            new Def(OPLOG_READ, "查看操作日志", "操作日志", "审计", "操作日志列表"),
            new Def(OPLOG_CLEAR, "清空操作日志", "操作日志", "审计", "清空全部审计记录（敏感）")
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
     * 「上级权限点 → 它包含的下级权限点」。模块级 {@code x:write} 保留<b>完全控制</b>语义：
     * 勾了它就等于勾了该模块全部按钮级编码，改造前已有的角色不会因拆细而丢权限。
     * 刻意<b>不</b>按前缀自动推导：否则 {@code channel:write} 会推出 {@code channel:ptz}，
     * 「能改通道名」的角色就顺带能转云台了。
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
     * 展开一组权限点。含 {@link #ALL} 时返回<b>当前目录的全部编码</b>而非 {@code "*"}，
     * 前端 {@code permissions.includes('device:write')} 才能直接生效。
     * {@code x:write} 会展开成 {@link #IMPLIED} 声明的下级动作；未知编码<b>丢弃</b>
     * （角色表可能残留已下线的权限点，静默忽略比让它参与判断安全）。
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
                CHANNEL_READ, CHANNEL_WRITE,
                CHANNEL_PTZ, CHANNEL_PRESET, CHANNEL_PTZ_DEVICE, CHANNEL_PTZ_ADVANCED,
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
