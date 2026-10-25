package io.github.rurulalanana.starter.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 可热更新配置项注册表。
 *
 * <p><b>为什么需要注册表而不是直接读写 {[@code gb_config}</b>：
 * <ul>
 *   <li>每个配置需要知道<b>默认值</b>（yml 兜底）、<b>类型</b>（界面控件）、
 *       <b>取值范围</b>（防止把保留天数填成 0 把录像全删光）、
 *       <b>说明</b>（界面上要告诉运维「改这个会影响什么」）；</li>
 *   <li>集中在一处，新增配置只改这个文件，不用同时动 Service / Controller / 前端。</li>
 * </ul>
 *
 * <p><b>哪些配置可以放这里</b>：改了之后「下一次使用该值时自然生效」的
 * 运维参数 —— 保留天数、清理间隔、阈值、开关。
 * <b>不能放</b>：端口、文件路径、数据库连接、ZLM 启动参数等决定进程装配的项，
 * 它们改了必须重启，放进注册表只会造成「界面显示改了实际没生效」的误解。
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    // ==================== 录像 ====================

    /**
     * 录像保留天数。
     *
     * <p>超过就被清理任务删掉（跳过 important=true 的）。0 是特殊值：
     * 表示「不按天数删」，只受磁盘水位约束 —— 但界面上必须显式提示，
     * 否则运维填 0 会以为「永不删」，实际盘满时仍会被水位策略清掉。
     */
    public static final String RECORD_RETAIN_DAYS = "record.retain-days";

    /** 磁盘使用率上限（%），超过就删最老的录像。 */
    public static final String RECORD_DISK_MAX_USED_PERCENT = "record.disk-max-used-percent";

    /** 磁盘告急时必须保留的最近天数。 */
    public static final String RECORD_KEEP_MIN_DAYS = "record.keep-min-days";

    /**
     * 单文件分片时长（秒）。
     *
     * <p>改这个值<b>不影响正在录制的分片</b>（ZLM 在 mk_recorder_start 时就定下了
     * max_second），下一次开始录像才生效。界面上必须说明，否则运维会以为
     * 「改小了当前文件马上切」。
     */
    public static final String RECORD_MAX_SECOND = "record.max-second";

    /** 录像清理任务间隔（毫秒）。 */
    public static final String RECORD_PURGE_INTERVAL_MS = "record.purge-interval-ms";

    /** 云端录像保活间隔（毫秒）：确保开了录像开关的通道持续有流。 */
    public static final String RECORD_KEEPALIVE_INTERVAL_MS = "record.keepalive-interval-ms";

    /** 录像对账间隔（毫秒）：补归档丢失下线事件的录像。 */
    public static final String RECORD_RECONCILE_INTERVAL_MS = "record.reconcile-interval-ms";

    /** 录像下载/播放地址是否要求签名。关闭后知道路径即可下载。 */
    public static final String RECORD_URL_SIGN_ENABLED = "record.url-sign-enabled";

    /** 录像地址签名有效期（秒）。 */
    public static final String RECORD_URL_SIGN_TTL_SECONDS = "record.url-sign-ttl-seconds";

    // ==================== HTTPS ====================

    /**
     * HTTPS 监听端口。
     *
     * <p><b>为什么端口要能改</b>：国内云服务器经常因备案限制而无法使用 80/443，
     * 或者端口已被别的站点占用。写死在 yml 里意味着「为了换个端口得停机重启」，
     * 而改端口本身就是运维最常做的一件事。
     *
     * <p>改成运行时配置后，在界面上改完 → 重新生成 nginx 配置并 reload → 立即生效，
     * 不需要重启应用（也不需要重新上传证书 —— 证书文件还在原处）。
     */
    public static final String TLS_PORT = "tls.port";

    /**
     * 是否允许应用自动 reload nginx。
     *
     * <p><b>这是一个提权面</b>：开启时应用能以 root 身份执行 {@code nginx -s reload}。
     * 关闭后上传证书仍会生成配置并做 {@code nginx -t} 校验，只是不自动 reload，
     * 界面会提示需要手工执行。高安全要求环境建议关闭。
     */
    public static final String TLS_AUTO_RELOAD = "tls.auto-reload";

    // ==================== 日志 ====================

    /** 操作日志保留天数。 */
    public static final String LOG_OPLOG_RETAIN_DAYS = "log.oplog-retain-days";

    /** 状态变更日志保留天数。 */
    public static final String LOG_STATUS_RETAIN_DAYS = "log.status-retain-days";

    /** 报警记录保留天数。 */
    public static final String ALARM_RETAIN_DAYS = "alarm.retain-days";

    /** 移动位置（GPS 轨迹）记录保留天数。 */
    public static final String POSITION_RETAIN_DAYS = "position.retain-days";

    /** 设备录像索引（设备侧文件清单缓存）保留天数。 */
    public static final String DEVICE_RECORD_RETAIN_DAYS = "device.record-retain-days";

    // ==================== 流 / 任务 ====================

    /** 空闲流回收间隔（毫秒）。 */
    public static final String STREAM_IDLE_REAP_INTERVAL_MS = "stream.idle-reap-interval-ms";

    /** 空闲流「无观看者」判定时长（毫秒），关流前等待的时间。 */
    public static final String STREAM_NONE_READER_DELAY_MS = "stream.none-reader-delay-ms";

    /** 设备离线判定阈值（秒）：超过该时长未收到心跳即标记离线。 */
    public static final String DEVICE_OFFLINE_SECONDS = "device.offline-seconds";

    /** 离线巡检间隔（毫秒）：多久扫一次「心跳超时」的设备。 */
    public static final String DEVICE_OFFLINE_SCAN_INTERVAL_MS = "device.offline-scan-interval-ms";

    /** 设备上线后是否自动下发目录/信息/状态查询。 */
    public static final String DEVICE_AUTO_COLLECT = "device.auto-collect";

    /** 同一设备两次自动采集的最小间隔（秒），防止设备频繁重启被打爆。 */
    public static final String DEVICE_COLLECT_INTERVAL_SECONDS = "device.collect-interval-seconds";

    /** 采集时是否下发「设备信息」查询（厂商/型号/固件）。 */
    public static final String DEVICE_COLLECT_DEVICE_INFO = "device.collect-device-info";

    /** 采集时是否下发「设备状态」查询。 */
    public static final String DEVICE_COLLECT_DEVICE_STATUS = "device.collect-device-status";

    // ==================== 信令接入 ====================

    /**
     * 设备统一接入密码（SIP 注册鉴权）。
     *
     * <p>留空 = 不鉴权（任何设备都能注册）。设置后所有设备的 REGISTER
     * 都要通过 Digest 校验，密码错误返回 401。
     *
     * <p><b>只校验密码、不校验用户名</b>：各厂商在 Authorization 里填的
     * username 五花八门（SIP 域 / 平台编码 / 设备编号），强制校验会让
     * 大量正常设备注册失败。
     */
    public static final String SIP_PASSWORD = "sip.password";

    // ==================== 注册表 ====================

    /**
     * 全部可配置项。
     *
     * <p>顺序即界面展示顺序（按 group 分组，组内保持声明顺序）。
     */
    public static final List<Def> ALL = List.of(
            intDef(RECORD_RETAIN_DAYS, "7", "record", 0, 3650,
                    "云端录像保留天数。超过则被清理任务删除（跳过「重要」标记的录像）。"
                            + "填 0 表示不按天数删，只受磁盘水位约束 —— "
                            + "磁盘满时仍会被清理，不要误以为 0 等于永久保留。"),
            intDef(RECORD_DISK_MAX_USED_PERCENT, "85", "record", 50, 95,
                    "磁盘使用率上限（%）。超过就删最老的录像直到降回阈值以下。"
                            + "上限只到 95：数据库、日志、转码临时文件与录像通常同盘，"
                            + "写满会让整台机器上的服务一起瘫痪，必须留出缓冲。"),
            intDef(RECORD_KEEP_MIN_DAYS, "1", "record", 1, 90,
                    "磁盘告急时也必须保留的最近天数（防止阈值配错把录像清空）。"
                            + "上限只到 90：这个值越大，「盘满时能删的东西」越少；"
                            + "设成远超磁盘容量的天数时，水位清理将无文件可删，"
                            + "磁盘会一直满着 —— 那是最坏的情况。"),
            intDef(RECORD_MAX_SECOND, "1800", "record", 60, 3600,
                    "单个录像文件的时长（秒），到点由 ZLM 自动切片。"
                            + "这同时也是「进程异常崩溃时最多丢失多久录像」的上限。"
                            + "注意：改此值不影响正在录制的文件，下一次开始录像才生效。"),
            longDef(RECORD_PURGE_INTERVAL_MS, "600000", "record", 60_000, 3_600_000,
                    "录像清理任务间隔（毫秒，默认 10 分钟）。"
                            + "上限只到 1 小时：清理与磁盘水位判定在同一个任务里，"
                            + "间隔过大会让磁盘在两次检查之间被写满。"),
            longDef(RECORD_KEEPALIVE_INTERVAL_MS, "30000", "record", 10_000, 120_000,
                    "云端录像保活间隔（毫秒）。确保开了录像开关的通道持续有流，"
                            + "设备掉线恢复后靠它自动重新拉流。"
                            + "这个间隔等于「流意外中断后，录像最多缺多久」，"
                            + "所以上限只到 2 分钟 —— 再大会在云端录像里留下一段段空洞。"),
            longDef(RECORD_RECONCILE_INTERVAL_MS, "60000", "record", 10_000, 1_800_000,
                    "录像对账间隔（毫秒）。把磁盘上已存在但库里缺记录的录像补登记回来。"
                            + "只影响「列表里多久能看到」，不影响录像本身，故范围可宽一些。"),
            boolDef(RECORD_URL_SIGN_ENABLED, "true", "record",
                    "录像播放/下载地址是否必须带签名。开启后地址形如 /record/xx.mp4?e=过期时间&t=签名，"
                            + "签名由服务端按「路径 + 过期时间」现算，过期即失效，无法跨用户转发。"
                            + "关闭后只要知道路径就能下载，仅建议在纯内网、无外网入口的部署里关闭。"
                            + "注意：本项只影响服务端是否校验，nginx 侧一律走鉴权子请求，改这项不需要重启或改 nginx。"),
            intDef(RECORD_URL_SIGN_TTL_SECONDS, "43200", "record", 300, 604_800,
                    "录像地址签名有效期（秒，默认 12 小时）。"
                            + "它同时是「播放页签最长能连续播多久」和「复制出去的链接多久失效」。"
                            + "下限 5 分钟：太小会导致长片播到一半续传被拒（每次 Range 都要重新校验）。"),
            intDef(TLS_PORT, "48087", "tls", 1, 65_535,
                    "HTTPS 监听端口（默认 48087）。"
                            + "选 48087 而不是 443 是因为国内云服务器常因备案限制而无法使用 80/443，"
                            + "而且 443 很可能已被同机其它站点占用。"
                            + "改完点「重新生成配置」即可生效，不需要重启应用、也不需要重新上传证书。"
                            + "注意：小于 1024 的端口需要 nginx 以 root 启动。"),
            boolDef(TLS_AUTO_RELOAD, "true", "tls",
                    "是否允许本应用自动 reload nginx。开启时应用能以 root 身份执行 nginx -s reload——"
                            + "这是一个提权面，高安全要求环境建议关闭。"
                            + "关闭后上传证书仍会生成配置并做语法校验，只是不自动 reload，需手工执行。"),

            intDef(LOG_OPLOG_RETAIN_DAYS, "7", "log", 1, 3650,
                    "操作日志保留天数（谁在什么时候做了什么）。"),
            intDef(LOG_STATUS_RETAIN_DAYS, "30", "log", 1, 3650,
                    "设备/通道状态变更日志保留天数。它是排查「某天为什么掉线」的关键证据，"
                            + "建议比操作日志留得更久。"),
            intDef(ALARM_RETAIN_DAYS, "180", "data", 1, 3650,
                    "报警记录保留天数（默认 180 天）。"
                            + "移动侦测类报警在高频点位上每天可产生上千条，不清理几个月就会把库撑大，"
                            + "而报警列表按发生时间倒序分页，表越大翻页越慢，最终表现是页面打不开。"
                            + "报警通常按季度复盘，180 天是「该看的都在」与「库不胀」的平衡点。"),
            intDef(POSITION_RETAIN_DAYS, "30", "data", 1, 3650,
                    "移动位置（GPS 轨迹）记录保留天数（默认 30 天）。"
                            + "车载/单兵设备按固定周期上报，一台设备一天可达数百到上万条，"
                            + "是增长最快的表。轨迹用于回溯「最近去过哪」，不需要长期保留。"),
            intDef(DEVICE_RECORD_RETAIN_DAYS, "30", "data", 1, 3650,
                    "设备侧录像索引（文件清单缓存）保留天数（默认 30 天）。"
                            + "它只是「设备录像检索」的结果缓存，真实清单在设备上、随时可重新检索，"
                            + "所以保留期不必长。不清的后果是每次检索都要在越来越大的表里查重。"),

            longDef(STREAM_IDLE_REAP_INTERVAL_MS, "20000", "stream", 5_000, 600_000,
                    "空闲流回收巡检间隔（毫秒）。无观看者且无录像的流会被关闭以释放设备带宽。"
                            + "巡检本身很轻（只查 ZLM 的读者数），间隔不宜太大。"),
            longDef(STREAM_NONE_READER_DELAY_MS, "45000", "stream", 20_000, 600_000,
                    "「无人观看」自动关流的等待时长（毫秒）。"
                            + "下限 20 秒：浏览器从点播到真正建立拉流要 1~3 秒"
                            + "（加载播放器 + 等首个关键帧），平台自身的空闲流巡检"
                            + "也有 20 秒下限，填更小没有意义，只会让刚打开的页面被误关。"
                            + "太大会让已关闭的页面继续占用设备带宽。"),

            intDef(DEVICE_OFFLINE_SECONDS, "180", "device", 120, 3600,
                    "设备离线判定阈值（秒）：超过该时长未收到心跳即标记离线。"
                            + "GB/T 28181 规定心跳周期 60 秒，下限因此定为 120 秒（2 倍）—— "
                            + "设得更小的话，设备在两次心跳之间就会被判离线，"
                            + "界面上会看到设备反复上下线，云端录像也跟着被反复中断。"
                            + "推荐 180~300 秒。"),
            longDef(DEVICE_OFFLINE_SCAN_INTERVAL_MS, "60000", "device", 10_000, 600_000,
                    "离线巡检间隔（毫秒）：多久扫一次「超过阈值没心跳」的设备。"
                            + "它决定的是「设备真的掉线后，界面多久变灰」，"
                            + "与判定阈值相加才是实际感知延迟。"),
            boolDef(DEVICE_AUTO_COLLECT, "true", "device",
                    "设备注册上线后，是否自动下发目录/信息/状态查询。"
                            + "关掉后只能手动点「采集」——内网设备很多时可以关掉减少信令量，"
                            + "但通道列表也会停止自动刷新。"),
            intDef(DEVICE_COLLECT_INTERVAL_SECONDS, "300", "device", 30, 86400,
                    "同一设备两次自动采集的最小间隔（秒）。"
                            + "设备频繁重启会反复注册，靠这个节流避免把它打爆。"
                            + "下限 30 秒：再短会在设备重连风暴时产生大量查询信令。"),
            boolDef(DEVICE_COLLECT_DEVICE_INFO, "true", "device",
                    "采集时是否下发「设备信息」查询（厂商/型号/固件版本）。"
                            + "设备不实现该命令时会返回错误，可关掉以减少无效信令。"),
            boolDef(DEVICE_COLLECT_DEVICE_STATUS, "true", "device",
                    "采集时是否下发「设备状态」查询（在线/录像/告警等）。"),
            strDef(SIP_PASSWORD, "", "sip",
                    "设备统一接入密码（SIP 注册鉴权）。留空表示不鉴权，任何设备都能注册；"
                            + "填写后所有设备的 REGISTER 都要通过 Digest 校验，密码错误返回 401。"
                            + "只校验密码、不校验用户名 —— 各厂商在 Authorization 里填的 username "
                            + "五花八门，强制校验会让大量正常设备注册失败。"
                            + "修改后立即生效，但已注册的设备不受影响，"
                            + "要等到下次注册/重连才会用新密码校验。")
    );

    /** 按 key 查定义；不存在返回 {@code null}。 */
    public static Def find(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Def d : ALL) {
            if (d.key.equals(k)) {
                return d;
            }
        }
        return null;
    }

    private static Def intDef(String key, String def, String group, long min, long max, String desc) {
        return new Def(key, def, group, "INT", min, max, desc);
    }

    private static Def longDef(String key, String def, String group, long min, long max, String desc) {
        return new Def(key, def, group, "LONG", min, max, desc);
    }

    /**
     * 布尔项。
     *
     * <p>min/max 传 0：{@code Def#validate} 对 BOOLEAN 走的是
     * true/false、1/0、on/off 归一化，不看范围。
     */
    private static Def boolDef(String key, String def, String group, String desc) {
        return new Def(key, def, group, "BOOLEAN", 0, 0, desc);
    }

    /**
     * 字符串项（如密码）。
     *
     * <p>min/max 传 0：不参与数值校验。
     */
    private static Def strDef(String key, String def, String group, String desc) {
        return new Def(key, def, group, "STRING", 0, 0, desc);
    }

    /**
     * 一项配置的定义。
     *
     * @param key     配置键
     * @param def     默认值（与 application.yml 保持一致；yml 缺失时的兜底）
     * @param group   分组（前端分区展示）
     * @param type    INT / LONG / BOOLEAN / STRING
     * @param min     数值下限（非数值类型填 0）
     * @param max     数值上限（非数值类型填 0）
     * @param desc    界面说明
     */
    public record Def(String key, String def, String group, String type,
                      long min, long max, String desc) {

        /** 校验并规范化一个候选值；不合法时抛 {@link IllegalArgumentException}。 */
        public String validate(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("配置值不能为空：" + key);
            }
            String v = raw.trim();
            // STRING 允许空串：空是该类型的一个合法取值，不是「没填」。
            // 例如 sip.password 的语义就是「留空 = 关闭注册鉴权」——
            // 若在这里一并拒绝，鉴权开关会变成单向门（能开不能关），
            // 运维只能去库里手改，极其危险。
            if (v.isEmpty() && !"STRING".equals(type)) {
                throw new IllegalArgumentException("配置值不能为空：" + key);
            }
            switch (type) {
                case "INT", "LONG" -> {
                    long n;
                    try {
                        n = Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                key + " 必须是整数，收到：" + v);
                    }
                    if (n < min || n > max) {
                        throw new IllegalArgumentException(
                                key + " 必须在 " + min + " ~ " + max + " 之间，收到：" + n);
                    }
                }
                case "BOOLEAN" -> {
                    String lower = v.toLowerCase(Locale.ROOT);
                    if (!Arrays.asList("true", "false", "1", "0", "on", "off").contains(lower)) {
                        throw new IllegalArgumentException(
                                key + " 必须是布尔值（true/false），收到：" + v);
                    }
                    v = ("true".equals(lower) || "1".equals(lower) || "on".equals(lower))
                            ? "true" : "false";
                }
                default -> {
                    // STRING：不做范围校验
                }
            }
            return v;
        }
    }
}
