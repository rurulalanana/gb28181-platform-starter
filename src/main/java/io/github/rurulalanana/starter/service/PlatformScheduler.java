package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AlarmService alarmService;
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

    /*
     * 保留天数 / 磁盘水位 / 任务间隔一律从 {@link RuntimeConfigService} 实时读取，
     * 不在这里用 {@code @Value} 缓存一份。
     *
     * <p>早先这里确实有 5 个 {@code @Value} 字段（oplogRetainDays 等），
     * 但自从配置改为存库热更新后它们就再没被读过 ——
     * 留着只会让人以为「改 yml 能生效」，与类注释里的说明恰好相反。
     */

    /**
     * 心跳超时巡检。
     *
     * <p><b>间隔也走运行时配置</b>：{@code device.offline-scan-interval-ms}
     * 在界面上可改，所以不能用 {@code @Scheduled(fixedDelayString=...)} 固定
     * （那表达式只在启动时求值一次，改完不生效）。改成「固定 tick + due 判断」，
     * 与其它可调间隔的任务保持一致。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void scanOfflineDevices() {
        if (!due("offline-scan", config.getLong(ConfigKeys.DEVICE_OFFLINE_SCAN_INTERVAL_MS))) {
            return;
        }
        try {
            /*
             * 必须从 {@link RuntimeConfigService} 实时读取，不能用
             * {@code props.getStarter().getDeviceOfflineSeconds()}。
             *
             * <p>后者是启动时绑定的 yml 快照：`device.offline-seconds` 在界面上
             * 是可热更新的（存库 + 立即生效），但这里读 props 就永远不会变，
             * 表现为「界面上保存成功、阈值实际没变」。实测过这个坑 ——
             * 其余清理任务都已改成 config.getXxx()，唯独这一处漏了。
             */
            int timeoutSeconds = config.getInt(ConfigKeys.DEVICE_OFFLINE_SECONDS);
            int count = deviceService.offlineSilentDevices(timeoutSeconds);
            if (count > 0) {
                log.info("离线巡检：{} 个设备因心跳超时被标记离线（阈值 {} 秒）",
                        count, timeoutSeconds);
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
     * 报警 / 移动位置 / 设备录像索引的过期清理（每小时一次）。
     *
     * <p><b>这三张表此前完全没有清理路径</b>，是比操作日志更严重的隐患：
     * <ul>
     *   <li>{@code gb_alarm}：移动侦测类报警在高频点位上每天上千条；</li>
     *   <li>{@code gb_position_log}：车载/单兵设备按固定周期上报 GPS，
     *       一台设备一天就有数百到上万条；</li>
     *   <li>{@code gb_device_record}：每次「设备录像检索」都写入设备侧文件清单。</li>
     * </ul>
     * 跑几个月就能把库撑到几十 GB，而这些表都参与按时间倒序的分页查询 ——
     * 表现是「报警页面越用越慢，最后打不开」，且清理只能靠 DBA 手工 DELETE。
     *
     * <p>保留期与报警/位置的实际用途挂钩：
     * 报警通常按「季度复盘」看，位置轨迹更短（一周足够回溯），
     * 设备录像索引只是缓存（设备端的录像清单随时可以重新检索），所以最短。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void purgeUnboundedTables() {
        if (!due("unbounded-purge", 3_600_000L)) {
            return;
        }
        int alarmDays = config.getInt(ConfigKeys.ALARM_RETAIN_DAYS);
        int positionDays = config.getInt(ConfigKeys.POSITION_RETAIN_DAYS);
        int deviceRecordDays = config.getInt(ConfigKeys.DEVICE_RECORD_RETAIN_DAYS);
        try {
            int alarms = alarmService.purgeExpired(alarmDays);
            if (alarms > 0) {
                log.info("报警清理：{} 条超过 {} 天的记录已删除", alarms, alarmDays);
            }
        } catch (Exception e) {
            log.warn("报警清理失败: {}", e.getMessage());
        }
        try {
            int positions = deviceService.purgePositionLogs(positionDays);
            if (positions > 0) {
                log.info("位置记录清理：{} 条超过 {} 天的记录已删除", positions, positionDays);
            }
        } catch (Exception e) {
            log.warn("位置记录清理失败: {}", e.getMessage());
        }
        try {
            int cached = deviceService.purgeDeviceRecords(deviceRecordDays);
            if (cached > 0) {
                log.info("设备录像索引清理：{} 条超过 {} 天的缓存已删除", cached, deviceRecordDays);
            }
        } catch (Exception e) {
            log.warn("设备录像索引清理失败: {}", e.getMessage());
        }
    }

    /**
     * 录像磁盘清理（默认每 10 分钟一次，启动 2 分钟后首跑）。
     *
     * <p>两级策略：
     * <ol>
     *   <li><b>按保留天数</b>：超过 {@link ConfigKeys#RECORD_RETAIN_DAYS} 的非重要录像删掉；</li>
     *   <li><b>按磁盘水位</b>：用完 1 之后若仍超过
     *       {@link ConfigKeys#RECORD_DISK_MAX_USED_PERCENT}，继续删最老的
     *       （跳过 important 与最近 {@link ConfigKeys#RECORD_KEEP_MIN_DAYS} 天）。</li>
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
            int deleted = recordService.purgeOldestUntilFree(
                    props.resolvedRecordRoot(), maxUsedPercent, keepMinDays);
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
     * <h3>语义：只产生一个内存信号，绝不改用户的配置</h3>
     *
     * <p>本方法<b>不写数据库、不改 {@code cloud_record}、不改 {@code on_demand}</b>，
     * 只把「此刻计划判定应录」的通道集合写进
     * {@link ChannelRecordPolicy#replacePlanActive}。
     *
     * <p>早先的实现让计划直接改这两个字段，后果：
     * <ul>
     *   <li>用户手动设的开关被计划反复覆盖，界面上看到状态自己变，无法维护；</li>
     *   <li>计划把 {@code on_demand} 设成 false 后，空闲流永远不被回收
     *       （见 {@code StreamService#channelsRequiringStream}），
     *       设备被一直拉着推流、白占带宽。</li>
     * </ul>
     * 现在「是否录像」= 用户手动开关 ∪ 计划信号，两者独立，
     * 用户的持久配置完全由用户掌握。
     *
     * <p><b>优先级</b>：用户手动开启优先。计划关闭时不会关掉用户手动开的通道 ——
     * 判定是并集，见 {@link ChannelRecordPolicy#isEnabled}。
     *
     * <p><b>多个计划包含同一通道</b>：取<b>并集</b>——任一计划处于时段内即应录制。
     * 这比「取交集」更符合直觉：用户给同一通道配了「白班 8-18」和「夜班 20-6」
     * 两个计划，期望是两段都录，而不是只在重叠时段录。
     * 该逻辑在 {@link RecordPlanService#scopeAt} 里，天然是 union。
     *
     * <p><b>只决策、不拉流</b>：开录由 {@link #keepCloudRecordStreams()} 保活任务
     * 负责（它保证流在线，流上线回调自动开录），避免两个任务同时发 INVITE 打架。
     *
     * <p>30 秒周期：计划最小粒度是「分钟」，30 秒足以在一个分钟内跟上，
     * 又不至于频繁查库（每轮 2 次查询）。
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
        // 只更新内存信号：不动 cloud_record / on_demand，不写库。
        // 记录替换前的集合，用于算出「进入」与「退出」两批，只在变化时打日志。
        java.util.Set<String> before = recordPolicy.planActiveChannels();
        java.util.Set<String> entered;
        try {
            entered = recordPolicy.replacePlanActive(scope.active());
        } catch (Exception e) {
            log.warn("更新录像计划生效集合失败: {}", e.getMessage());
            return;
        }
        // 退出 = 原本在、现在不在
        java.util.Set<String> exited = new java.util.HashSet<>(before);
        exited.removeAll(scope.active());

        if (!entered.isEmpty() || !exited.isEmpty()) {
            log.info("录像计划：{} 路进入时段、{} 路退出（计划覆盖 {} 路，当前应在录 {} 路）",
                    entered.size(), exited.size(),
                    scope.governed().size(), scope.active().size());
        }
        // 退出的通道由 keepCloudRecordStreams 的「停录对账」收尾：
        // 那边统一按「应录集合」比对，避免两处都去停录造成竞态。
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
        // 反向对账：正在录、但此刻不该录的通道 → 停录并归档。
        //
        // 【为什么必须在这里做】录像计划的「退出时段」只更新内存信号
        // （ChannelRecordPolicy#replacePlanActive），不直接停录 ——
        // 若两处都停会撞车。这里统一按「应录集合」对账，
        // 无论是计划退出、用户关了开关、还是计划被删除/停用，都在这一处收尾。
        stopRecordingsNotNeeded();
    }

    /**
     * 停掉「正在录但此刻不该录」的通道。
     *
     * <p>触发场景：
     * <ul>
     *   <li>录像计划退出时段（内存信号消失）；</li>
     *   <li>用户关掉通道的云端录像开关；</li>
     *   <li>计划被删除 / 停用，或通道从计划里移除。</li>
     * </ul>
     *
     * <p><b>为什么覆盖全部会话而不是只查计划通道</b>：
     * {@code recordPolicy.enabledChannels()} 是「应该录」的权威集合，
     * 拿它与「实际在录」比对即可，不必关心是哪个原因导致不该录。
     *
     * <p>停录会顺带归档（{@code stopCloudRecord} 内部落库），
     * 所以计划退出时段的最后一段不会丢。
     */
    private void stopRecordingsNotNeeded() {
        java.util.Set<String> shouldRecord = recordPolicy.enabledChannels();
        RecordService rs = recordService;
        for (RtpSession session : sessionsSnapshot()) {
            try {
                String channelId = session == null ? null : session.getChannelId();
                // 只处理实时会话：回放/下载会话不涉及录像
                if (channelId == null || session.getType() == null || session.getType().historical()) {
                    continue;
                }
                if (shouldRecord.contains(channelId)) {
                    continue;
                }
                if (!rs.isRecording(channelId)) {
                    continue;
                }
                log.info("通道 {} 已不在录像时段/开关已关，停止录像并归档", channelId);
                rs.stopCloudRecord(channelId);
            } catch (Exception e) {
                log.warn("停止多余录像失败: channel={} {}",
                        session == null ? "?" : session.getChannelId(), e.getMessage());
            }
        }
    }

    /** 当前会话快照（会话管理器未就绪时返回空列表）。 */
    private java.util.Collection<RtpSession> sessionsSnapshot() {
        try {
            java.util.List<RtpSession> list = streamService.sessions();
            return list == null ? java.util.List.of() : list;
        } catch (Exception e) {
            log.debug("读取会话列表失败: {}", e.getMessage());
            return java.util.List.of();
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
            // 宽限期从运行时配置实时读取（不传的话 StreamService 会读启动快照，
            // 导致界面上改大后仍按旧值提前关流）
            int closed = streamService.reapIdleStreams(
                    config.getLong(ConfigKeys.STREAM_NONE_READER_DELAY_MS));
            if (closed > 0) {
                log.info("空闲流巡检：已关闭 {} 路无观看者的流", closed);
            }
        } catch (Exception e) {
            log.debug("空闲流巡检失败: {}", e.getMessage());
        }
    }
}
