package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台定时任务。
 *
 * <p>职责（按调度周期从短到长）：
 * <ul>
 *   <li>系统指标采样（3 秒）；</li>
 *   <li>孤儿流认领（15 秒）；</li>
 *   <li>空闲流回收（20 秒）；</li>
 *   <li>目录分页批次回收（30 秒）、云端录像保活（30 秒）；</li>
 *   <li>心跳超时设备标记离线（60 秒）、录像对账（60 秒）；</li>
 *   <li>录像磁盘清理（10 分钟）；</li>
 *   <li>操作日志清理（1 小时）、状态日志清理（1 小时）。</li>
 * </ul>
 *
 * <p>通道只由设备上报的目录应答产生，这里不做任何兜底登记。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformScheduler {

    private final DeviceService deviceService;
    private final OperationLogService operationLogService;
    private final SystemMetricsService systemMetricsService;
    private final RecordService recordService;
    private final ChannelRecordPolicy recordPolicy;
    private final StreamService streamService;
    private final CatalogPager catalogPager;
    private final RecordPlanService recordPlanService;
    private final Gb28181Properties props;

    /**
     * 可热更新配置的来源（<b>数据库优先、application.yml 兜底</b>）。
     *
     * <p>所有「用户可能在界面上改」的参数都从这里读，<b>不要用 {@code @Value}</b>：
     * {@code @Value} 只在启动时解析一次，改 yml 或改库都不生效，必须重启 ——
     * 而这正是要消灭的运维成本。
     */
    private final RuntimeConfigService config;

    /**
     * 各动态任务上一次实际执行的时刻（任务名 → 毫秒）。
     *
     * <p><b>为什么需要它</b>：{@code @Scheduled(fixedDelayString = "${...}")}
     * 的占位符只在<b>启动时</b>解析一次，改了 yml 或数据库都不会生效。
     * 为了支持「界面上改间隔立即生效」，做法是：
     * <ul>
     *   <li>{@code @Scheduled} 固定高频 tick（{@link #TICK_MS}）；</li>
     *   <li>任务内部用 {@link #due} 判断「距上次执行是否已达最新配置的间隔」。</li>
     * </ul>
     * 代价是每个 tick 一次 HashMap 查找，相对任务本身耗时可以忽略。
     *
     * <p>附带好处：间隔调小<b>立刻</b>体现（下一 tick 就判定到期），
     * 调大则自然延后，都不需要重启。
     */
    private final Map<String, Long> lastRunAt = new ConcurrentHashMap<>();

    /**
     * 动态任务的调度心跳（毫秒）。
     *
     * <p>取 5 秒：抱证实际间隔误差不超过 5 秒，又不至于空转太频繁。
     * 现有可配置间隔的下限都是 5~10 秒级，5 秒 tick 足够表达。
     */
    private static final long TICK_MS = 5_000;

    /**
     * 判断某个周期任务本次是否该执行。
     *
     * <p>首次调用直接返回 {@code true}（允许立刻跑一次），之后按
     * {@link RuntimeConfigService} 里的<b>当前</b>间隔判断。
     *
     * @param taskId     任务标识（{@link #lastRunAt} 的 key）
     * @param intervalMs 间隔（毫秒，调用方从 config 实时读取）
     */
    private boolean due(String taskId, long intervalMs) {
        long now = System.currentTimeMillis();
        Long prev = lastRunAt.get(taskId);
        if (prev != null && now - prev < Math.max(intervalMs, TICK_MS)) {
            return false;
        }
        lastRunAt.put(taskId, now);
        return true;
    }

    /**
     * 会话停在 {@code NEGOTIATING} 多久才允许重建一次（毫秒）。
     *
     * <p>取值权衡：太短等于没退避（30 秒一轮照旧轰炸设备），
     * 太长会让「设备刚恢复」的通道多等很久。2 分钟是折中 ——
     * 正常设备收到 INVITE 后 1~2 秒就开始推流，
     * 能拖过 2 分钟的基本是设备侧真有问题，慢点重试无妨。
     */
    private static final long STUCK_RETRY_MS = 120_000;

    /**
     * 通道 → 首次观察到「协商中」的时间戳（毫秒）。
     *
     * <p>用来判断会话是「刚发起、还在等设备推流」还是「卡死了」。
     * 会话转 {@code ONLINE} 或消失时清除，避免长期占用内存。
     */
    private final Map<String, Long> negotiatingSince = new ConcurrentHashMap<>();

    /** 操作日志保留天数（默认 7 天）。 */
    @Value("${gb28181.starter.oplog-retain-days:7}")
    private int oplogRetainDays;

    /**
     * 云端录像保留天数（默认 7 天）；{@code <=0} 表示不按天数删，只受磁盘水位约束。
     *
     * <p>配合 {@link #recordDiskMaxUsedPercent} 使用：天数保证「至少留 7 天」，
     * 水位保证「盘不能满」。单通道 24 小时录像约 350MB/小时，
     * 30G 的盘只够 3 天左右，因此水位兜底是必需的。
     */
    @Value("${gb28181.starter.record-retain-days:7}")
    private int recordRetainDays;

    /**
     * 录像磁盘使用率上限（百分比，默认 85）。
     *
     * <p>超过就删最老的录像（跳过 important 与最近
     * {@link #recordKeepMinDays} 天），直到降回阈值以下。
     *
     * <p>为什么不是 95：留出 15% 给数据库、日志、临时转码文件。
     * 同盘写满时 PostgreSQL 报错会连带整个平台不可用，比丢老录像严重得多。
     */
    @Value("${gb28181.starter.record-disk-max-used-percent:85}")
    private int recordDiskMaxUsedPercent;

    /** 磁盘告急时，无论多大压力都至少保留最近几天（默认 1 天）。 */
    @Value("${gb28181.starter.record-keep-min-days:1}")
    private int recordKeepMinDays;

    /** 状态变更日志保留天数（默认 30 天）；{@code <=0} 表示不清理。 */
    @Value("${gb28181.starter.status-log-retain-days:30}")
    private int statusLogRetainDays;

    /** 心跳超时巡检，默认每 60 秒一次。 */
    @Scheduled(fixedDelayString = "${gb28181.starter.offline-scan-interval-ms:60000}")
    public void scanOfflineDevices() {
        try {
            int count = deviceService.offlineSilentDevices(props.getStarter().getDeviceOfflineSeconds());
            if (count > 0) {
                log.info("离线巡检：{} 个设备因心跳超时被标记离线", count);
            }
        } catch (Exception e) {
            log.warn("离线巡检失败: {}", e.getMessage());
        }
    }

    /**
     * 系统指标采样（默认每 3 秒，与前端轮询间隔一致）。
     *
     * <p><b>为什么由服务端定时采而不是等前端请求</b>：历史曲线要的是
     * 「过去 3 分钟」，而请求只在有人开页面时才发生。若靠请求驱动，
     * 第一个打开概览页的人只能看到一个点，得等 3 分钟才成线。
     * 这里恒定频率积累，页面打开即有完整曲线。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.metrics-sample-interval-ms:3000}")
    public void sampleSystemMetrics() {
        systemMetricsService.sample();
    }

    /**
     * 目录分页批次回收（默认每 30 秒）。
     *
     * <p>正常情况下每帧都会在 {@code CatalogPager.accept} 里被收口。
     * 但若设备少报 {@code SumNum}（报了 3 实际只发 2 帧）后续再无帧到达，
     * 该批次就没人来收口，会永久占着内存。这里定期扫掉超时批次。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.catalog-pager-purge-interval-ms:30000}")
    public void purgeCatalogPager() {
        try {
            int removed = catalogPager.purgeExpired();
            if (removed > 0) {
                log.warn("目录分页批次超时回收：{} 个（设备少报 SumNum 或应答丢包）", removed);
            }
        } catch (Exception e) {
            log.warn("目录分页批次回收失败: {}", e.getMessage());
        }
    }

    /** 操作日志过期清理（每小时，保留天数由 {@link ConfigKeys#LOG_OPLOG_RETAIN_DAYS} 控制）。 */
    @Scheduled(fixedDelay = TICK_MS)
    public void purgeOperationLogs() {
        if (!due("oplog-purge", 3_600_000L)) {
            return;
        }
        int retainDays = config.getInt(ConfigKeys.LOG_OPLOG_RETAIN_DAYS);
        try {
            int deleted = operationLogService.purgeExpired(retainDays);
            if (deleted > 0) {
                log.info("操作日志清理：{} 条过期记录已删除（保留 {} 天）", deleted, retainDays);
            }
        } catch (Exception e) {
            log.warn("操作日志清理失败: {}", e.getMessage());
        }
    }

    /**
     * 状态变更日志过期清理（默认每小时一次，保留 30 天）。
     *
     * <p><b>为什么需要</b>：{@code gb_status_log} 记录设备/通道的在线离线变更，
     * 每次心跳超时、每次重连都写一条。实测 10 天积累了 1 万条 ——
     * 量不大但<b>没有任何清理路径</b>，跑一年就是 36 万条，
     * 而它参与 {@code ORDER BY create_time DESC} 的分页查询，
     * 表越大翻页越慢。
     *
     * <p>保留期比操作日志（7 天）长得多：状态变更是排查
     * 「某天设备为什么掉线」的关键证据，比谁点了哪个按钮更值得留。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void purgeStatusLogs() {
        if (!due("status-log-purge", 3_600_000L)) {
            return;
        }
        int retainDays = config.getInt(ConfigKeys.LOG_STATUS_RETAIN_DAYS);
        if (retainDays <= 0) {
            return;
        }
        try {
            int deleted = deviceService.purgeStatusLogs(retainDays);
            if (deleted > 0) {
                log.info("状态日志清理：{} 条超过 {} 天的记录已删除", deleted, retainDays);
            }
        } catch (Exception e) {
            log.warn("状态日志清理失败: {}", e.getMessage());
        }
    }

    /**
     * 录像磁盘清理（默认每 10 分钟一次，启动 2 分钟后首跑）。
     *
     * <p>两级策略：
     * <ol>
     *   <li><b>按保留天数</b>：超过 {@link #recordRetainDays} 的非重要录像删掉；</li>
     *   <li><b>按磁盘水位</b>：用完 1 之后若仍超过
     *       {@link #recordDiskMaxUsedPercent}，继续删最老的
     *       （跳过 important 与最近 {@link #recordKeepMinDays} 天）。</li>
     * </ol>
     *
     * <p><b>为什么必须有</b>：云端录像 24 小时不停写，单通道约 8GB/天。
     * 服务器只有 30G 盘，没有清理就是一颗定时炸弹 —— 磁盘写满后
     * ZLM 分片写失败、PostgreSQL（同盘）也可能写失败，整个平台瘫痪。
     *
     * <p>跑在 Spring 的调度线程上，不与 {@code gb28181-persist} 抢（删文件是 I/O，
     * 落库是事务，混在一个线程会互相拖慢）。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void purgeRecordings() {
        long interval = config.getLong(ConfigKeys.RECORD_PURGE_INTERVAL_MS);
        if (!due("record-purge", interval)) {
            return;
        }
        int retainDays = config.getInt(ConfigKeys.RECORD_RETAIN_DAYS);
        int maxUsedPercent = config.getInt(ConfigKeys.RECORD_DISK_MAX_USED_PERCENT);
        int keepMinDays = config.getInt(ConfigKeys.RECORD_KEEP_MIN_DAYS);
        // 1. 按天清理
        try {
            int deleted = recordService.purgeExpiredRecords(retainDays);
            if (deleted > 0) {
                log.info("录像清理：{} 条超过保留期（{} 天）的记录已删除", deleted, retainDays);
            }
        } catch (Exception e) {
            log.warn("录像过期清理失败: {}", e.getMessage());
        }
        // 2. 磁盘水位兜底
        try {
            String root = props.getRecordRoot();
            if (root == null || root.isBlank()) {
                root = System.getProperty("user.dir") + "/record";
            }
            int deleted = recordService.purgeOldestUntilFree(root, maxUsedPercent, keepMinDays);
            if (deleted > 0) {
                log.info("录像清理：磁盘水位超限，删除了 {} 条最老的录像", deleted);
            }
        } catch (Exception e) {
            log.warn("录像磁盘水位清理失败: {}", e.getMessage());
        }
    }

    /**
     * 录像计划执行（默认每 30 秒）。
     *
     * <p><b>为什么必须有</b>：{@link RecordPlanService} 原先只提供 CRUD，
     * <b>没有任何地方真正执行计划</b> —— 用户在界面上配好时段、关联通道后
     * 什么都不会发生。这是功能缺失，不是配置问题。
     *
     * <p><b>语义：计划对覆盖的通道有完全控制权</b>（与 LiveGBS 一致）：
     * 时段内云端录像应为开、时段外应为关，即使之前是手动开的。
     * 这样行为可预期；界面需明确提示，否则用户会困惑「手动开的怎么自己关了」。
     *
     * <p><b>只驱动「开关」，不直接拉流</b>：开启后由既有的
     * {@link #keepCloudRecordStreams()} 保活任务负责保证流在线，
     * 避免两个任务同时发 INVITE 打架。
     *
     * <p>30 秒周期：计划的最小粒度是「分钟」，30 秒足以在一个分钟内跟上，
     * 又不至于频繁查库（本任务每轮 2 次查询）。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void applyRecordPlans() {
        if (!recordPolicy.isLoaded()) {
            return;
        }
        if (!due("record-plan", 30_000L)) {
            return;
        }
        RecordPlanService.PlanScope scope;
        try {
            scope = recordPlanService.scopeAt(java.time.LocalDateTime.now());
        } catch (Exception e) {
            log.warn("录像计划计算失败: {}", e.getMessage());
            return;
        }
        if (scope.governed().isEmpty()) {
            return;
        }
        int started = 0;
        int stopped = 0;
        // 1) 时段内：确保开关为开
        for (String channelId : scope.active()) {
            try {
                if (recordPolicy.isEnabled(channelId)) {
                    continue;
                }
                GbChannel ch = deviceService.findChannel(channelId);
                if (ch == null) {
                    continue;
                }
                ch.setCloudRecord(true);
                // 互斥：计划要录像，就必须常开推流
                ch.setOnDemand(false);
                deviceService.updateChannel(ch);
                recordPolicy.set(channelId, true);
                started++;
            } catch (Exception e) {
                log.warn("录像计划开启失败: channel={} {}", channelId, e.getMessage());
            }
        }
        // 2) 时段外：确保开关为关（计划覆盖的通道由计划决定，即使曾手动开过）
        for (String channelId : scope.shouldStop()) {
            try {
                if (!recordPolicy.isEnabled(channelId)) {
                    continue;
                }
                GbChannel ch = deviceService.findChannel(channelId);
                if (ch == null) {
                    continue;
                }
                ch.setCloudRecord(false);
                deviceService.updateChannel(ch);
                recordPolicy.set(channelId, false);
                // 立即停录并归档：只关开关的话要等流被空闲回收才收尾，
                // 时段结束后还会多录好几分钟（用户看到的就是「计划不准」）。
                if (recordService.isRecording(channelId)) {
                    recordService.stopCloudRecord(channelId);
                }
                stopped++;
            } catch (Exception e) {
                log.warn("录像计划关闭失败: channel={} {}", channelId, e.getMessage());
            }
        }
        if (started > 0 || stopped > 0) {
            log.info("录像计划执行：开启 {} 路、关闭 {} 路（覆盖 {} 路通道，当前应在录 {} 路）",
                    started, stopped, scope.governed().size(), scope.active().size());
        }
    }

    /**
     * 自动录像对账（默认每 60 秒）。
     *
     * <p><b>为什么需要</b>：自动录像的归档挂在「流下线」事件上，但事件可能丢 ——
     * 平台重启（ZLM 内存索引清空）、设备拔线超时（直接 BYE 超时而非正常下线）。
     * 丢了事件的录像在 ZLM 侧已经结束，但库里永远缺一条终态记录。
     * 这里周期性把「已结束却未落库」的录像补归档，保证云端录像列表完整。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void reconcileRecordings() {
        if (!due("record-reconcile", config.getLong(ConfigKeys.RECORD_RECONCILE_INTERVAL_MS))) {
            return;
        }
        try {
            int archived = recordService.reconcileAutoRecordings();
            if (archived > 0) {
                log.info("录像对账：补归档 {} 条丢失下线事件的录像", archived);
            }
        } catch (Exception e) {
            log.warn("录像对账失败: {}", e.getMessage());
        }
    }

    /**
     * 云端录像保活：确保开了录像开关的通道持续有流（默认每 30 秒）。
     *
     * <p><b>为什么需要</b>：云端录像的前提是「有流可录」，而流的建立只发生在
     * 用户主动点播时。用户打开开关后不点播、或平台重启（ZLM 与 SIP 会话表都清空），
     * 这些通道就永远不会开始录 —— 表现为「开了云端录像却什么都没发生」。
     * 开关的语义是「平台持续把这段录像存下来」，不应依赖用户手动拉流。
     *
     * <p><b>不重复拉流</b>：会话已 {@code ONLINE} 时直接跳过，
     * 不产生任何信令。
     *
     * <p><b>失败要退避，不能每轮硬重建</b>：这是踩过的坑 ——
     * 设备侧不推流时（级联下级平台掉线、设备不认 INVITE），会话会长期停在
     * {@code NEGOTIATING}。{@code startLive} 的复用判断要求「ZLM 确实在收 RTP」，
     * 于是每轮都判定「不可复用」→ 关旧会话 → 重新 INVITE。
     * 实测表现为每 30 秒一次 BYE + INVITE，SSRC 逐次递增
     * （{@code 0100020013 → 0100020014 → …}），日志里刷满
     * 「已有会话，先关闭旧会话」，而录像一段都没生成。
     *
     * <p>现在按会话状态分三档处理：
     * <ul>
     *   <li>{@code ONLINE} → 跳过，什么都不做；</li>
     *   <li>{@code NEGOTIATING} 且停留时间 &lt; {@link #STUCK_RETRY_MS}
     *       → 跳过（给设备留出推流时间，正常设备 1~2 秒就开始推）；</li>
     *   <li>{@code NEGOTIATING} 超时或压根没会话 → 才重建一次，并重置计时。</li>
     * </ul>
     * 重试频率因此从「每 30 秒」降到「每 2 分钟」，对设备的压力小 4 倍，
     * 恢复速度也够用（设备一恢复，下一个周期就能建上）。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void keepCloudRecordStreams() {
        if (!recordPolicy.isLoaded()) {
            return;
        }
        if (!due("record-keepalive", config.getLong(ConfigKeys.RECORD_KEEPALIVE_INTERVAL_MS))) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String channelId : recordPolicy.enabledChannels()) {
            try {
                GbChannel ch = deviceService.findChannel(channelId);
                if (ch == null || !ch.isOnline()) {
                    // 设备不在线：清掉计时，等它上线后从头开始算
                    negotiatingSince.remove(channelId);
                    continue;
                }
                RtpSession session = streamService.liveSession(ch.getDeviceId(), channelId);
                if (session != null && RtpSession.ONLINE.equals(session.getState())) {
                    // 流正常在推，无需干预
                    negotiatingSince.remove(channelId);
                    continue;
                }
                if (session != null) {
                    /*
                     * 有会话但还没收到 RTP（NEGOTIATING）：先给一个窗口等设备推流。
                     * 正常设备收到 INVITE 后 1~2 秒就开始推，拖过一个窗口还没动静
                     * 才认为卡住了，允许重建一次。
                     */
                    long since = negotiatingSince.computeIfAbsent(channelId, k -> now);
                    if (now - since < STUCK_RETRY_MS) {
                        continue;
                    }
                    log.debug("云端录像保活：会话协商超时，重建一次 channel={} 已等待={}ms",
                            channelId, now - since);
                }
                // 两种情况走到这里：压根没会话，或协商已超时 → 建流并重置计时
                negotiatingSince.put(channelId, now);
                streamService.startLive(ch.getDeviceId(), channelId);
            } catch (Exception e) {
                // 设备无响应/通道离线很常见，不刷 WARN，下个周期自然重试
                log.debug("云端录像保活拉流失败: channel={} {}", channelId, e.getMessage());
            }
        }
    }

    /**
     * 孤儿流巡检：把「设备仍在推、平台却没会话」的流重新纳管。
     *
     * <p><b>为什么需要定时巡检</b>：{@code on_mk_media_publish} 回调只在流
     * <b>首次注册</b>时触发一次。而平台启动时设备可能已经开始推流了 ——
     * 实测恢复 SSRC 映射（{@code StreamEventBridge#restoreSsrcMappings}）完成于
     * 17:53:06.5，而设备推流触发回调在 17:53:05.1，<b>早了 1.4 秒</b>。
     * 回调错过就是永久错过：这路流会一直停在「未匹配到会话」状态，
     * 表现为「级联那边还在推流、平台这边一路都看不到」，直到设备自己超时。
     *
     * <p>巡检按 ZLM 里实际存在的媒体源（流名是 SSRC 十六进制串 = 未被纳管的标志）
     * 反查墓碑，重建会话。周期短（默认 15 秒）是因为孤儿流既占带宽又让用户
     * 看不到画面，越早恢复越好。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.orphan-adopt-interval-ms:15000}",
            initialDelayString = "${gb28181.starter.orphan-adopt-initial-delay-ms:20000}")
    public void adoptOrphanStreams() {
        try {
            int adopted = streamService.adoptOrphanStreams();
            if (adopted > 0) {
                log.info("孤儿流巡检：已重新纳管 {} 路设备仍在推但平台无会话的流", adopted);
            }
        } catch (Exception e) {
            log.debug("孤儿流巡检失败: {}", e.getMessage());
        }
    }

    /**
     * 空闲流巡检：关掉没有任何观看者的会话（默认每 20 秒）。
     *
     * <p><b>为什么必须自己巡检而不能只靠 ZLM 的无人观看回调</b>：
     * {@code on_mk_media_no_reader} <b>每个媒体源只触发一次</b>
     * （ZLM 内部 {@code _none_reader} 标志位），且只在读者数由非零降为零的那一刻触发。
     * 而下面两种场景回调都已经错过、且永不补发：
     * <ol>
     *   <li>平台重启后设备仍在推流 —— 回调在会话重建<b>之前</b>就已触发
     *       （实测相差 2 秒）；</li>
     *   <li>{@link #adoptOrphanStreams()} 重新纳管孤儿流时，流早已注册。</li>
     * </ol>
     * 实测后果：一路流挂了几十分钟不关、一直占带宽，用户反馈
     * 「这个脏的流没有走无人观看关流」。
     *
     * <p>与孤儿流巡检配合成闭环：孤儿流巡检负责「让它可见」，
     * 本任务负责「没人看就关掉」，两者都以 ZLM 的实际媒体源为准。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void reapIdleStreams() {
        if (!due("idle-reap", config.getLong(ConfigKeys.STREAM_IDLE_REAP_INTERVAL_MS))) {
            return;
        }
        try {
            int closed = streamService.reapIdleStreams();
            if (closed > 0) {
                log.info("空闲流巡检：已关闭 {} 路无观看者的流", closed);
            }
        } catch (Exception e) {
            log.debug("空闲流巡检失败: {}", e.getMessage());
        }
    }
}
