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

    // ==================== 日志 ====================

    /** 操作日志保留天数。 */
    public static final String LOG_OPLOG_RETAIN_DAYS = "log.oplog-retain-days";

    /** 状态变更日志保留天数。 */
    public static final String LOG_STATUS_RETAIN_DAYS = "log.status-retain-days";

    // ==================== 流 / 任务 ====================

    /** 空闲流回收间隔（毫秒）。 */
    public static final String STREAM_IDLE_REAP_INTERVAL_MS = "stream.idle-reap-interval-ms";

    /** 空闲流「无观看者」判定时长（毫秒），关流前等待的时间。 */
    public static final String STREAM_NONE_READER_DELAY_MS = "stream.none-reader-delay-ms";

    /** 设备离线判定阈值（秒）：超过该时长未收到心跳即标记离线。 */
    public static final String DEVICE_OFFLINE_SECONDS = "device.offline-seconds";

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
            intDef(RECORD_DISK_MAX_USED_PERCENT, "85", "record", 50, 99,
                    "磁盘使用率上限（%）。超过就删最老的录像直到降回阈值以下。"
                            + "不要设到 95 以上：必须给数据库、日志、转码临时文件留余量，"
                            + "同盘写满会让整个平台不可用。"),
            intDef(RECORD_KEEP_MIN_DAYS, "1", "record", 1, 365,
                    "磁盘告急时也必须保留的最近天数。防止阈值配置失误把录像清空。"),
            intDef(RECORD_MAX_SECOND, "1800", "record", 60, 3600,
                    "单个录像文件的时长（秒），到点由 ZLM 自动切片。"
                            + "这同时也是「进程异常崩溃时最多丢失多久录像」的上限。"
                            + "注意：改此值不影响正在录制的文件，下一次开始录像才生效。"),
            longDef(RECORD_PURGE_INTERVAL_MS, "600000", "record", 60_000, 86_400_000,
                    "录像清理任务间隔（毫秒，默认 10 分钟）。"),
            longDef(RECORD_KEEPALIVE_INTERVAL_MS, "30000", "record", 10_000, 600_000,
                    "云端录像保活间隔（毫秒）。确保开了录像开关的通道持续有流，"
                            + "设备掉线恢复后靠它自动重新拉流。"),
            longDef(RECORD_RECONCILE_INTERVAL_MS, "60000", "record", 10_000, 1_800_000,
                    "录像对账间隔（毫秒）。把磁盘上已存在但库里缺记录的录像补登记回来。"),

            intDef(LOG_OPLOG_RETAIN_DAYS, "7", "log", 1, 3650,
                    "操作日志保留天数（谁在什么时候做了什么）。"),
            intDef(LOG_STATUS_RETAIN_DAYS, "30", "log", 1, 3650,
                    "设备/通道状态变更日志保留天数。它是排查「某天为什么掉线」的关键证据，"
                            + "建议比操作日志留得更久。"),

            longDef(STREAM_IDLE_REAP_INTERVAL_MS, "20000", "stream", 5_000, 600_000,
                    "空闲流回收巡检间隔（毫秒）。无观看者且无录像的流会被关闭以释放设备带宽。"),
            longDef(STREAM_NONE_READER_DELAY_MS, "45000", "stream", 5_000, 600_000,
                    "「无人观看」自动关流的等待时长（毫秒）。"
                            + "太短会在前端播放器还没加载完就把流关掉（表现为点播很慢），"
                            + "太长会让关掉的页面继续占用设备带宽。"),

            intDef(DEVICE_OFFLINE_SECONDS, "180", "device", 30, 3600,
                    "设备离线判定阈值（秒）：超过该时长未收到心跳即标记离线。"
                            + "应大于设备心跳周期的 2~3 倍（心跳通常 60 秒）。")
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
            if (v.isEmpty()) {
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
