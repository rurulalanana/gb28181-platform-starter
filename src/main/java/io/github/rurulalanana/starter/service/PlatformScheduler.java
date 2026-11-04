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
 * 平台定时任务：指标采样（3s）、孤儿流认领（15s）、空闲流回收（20s）、目录分页回收 / 录像保活（30s）、
 * 心跳超时与录像对账（60s）、磁盘清理（10min）、日志清理（1h）。
 * 通道只由设备上报的目录应答产生，这里不做兜底登记。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformScheduler {

    private final DeviceService deviceService;
    private final AlarmService alarmService;
    /** 级联上行链路：注册 / 保活 / 重注册。 */
    private final CascadeLinkService cascadeLinkService;
    private final OperationLogService operationLogService;
    private final SystemMetricsService systemMetricsService;
    private final RecordService recordService;
    private final DownloadService downloadService;
    private final ChannelRecordPolicy recordPolicy;
    private final StreamService streamService;
    private final CatalogPager catalogPager;
    private final RecordPlanService recordPlanService;
    /** SDK 直连设备探测（它们没有心跳，只能主动探）。 */
    private final DeviceProvisionService provisionService;
    private final Gb28181Properties props;

    /**
     * 可热更新配置的来源（数据库优先、application.yml 兜底）。
     * 用户可能在界面上改的参数都从这里读，不用 {@code @Value}：它只在启动时解析一次。
     */
    private final RuntimeConfigService config;

    /**
     * 各动态任务上一次实际执行的时刻（任务名 → 毫秒）。
     * {@code @Scheduled(fixedDelayString = "${...}")} 的占位符只在启动时解析一次，
     * 所以固定高频 tick，任务内部用 {@link #due} 判断「距上次执行是否已达最新配置的间隔」。
     */
    private final Map<String, Long> lastRunAt = new ConcurrentHashMap<>();

    /** 动态任务的调度心跳（毫秒）：5 秒使实际间隔误差不超过 5 秒，又不至于空转太频繁。 */
    private static final long TICK_MS = 5_000;

    /**
     * 判断某个周期任务本次是否该执行：首次调用直接返回 {@code true}，
     * 之后按 {@link RuntimeConfigService} 的当前间隔判断。
     *
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
     * 正常设备收到 INVITE 后 1~2 秒就开始推流，能拖过 2 分钟的基本是设备侧真有问题。
     */
    private static final long STUCK_RETRY_MS = 120_000;

    /**
     * 通道 → 首次观察到「协商中」的时间戳（毫秒）：判断会话是等设备推流还是卡死。
     * 会话转 {@code ONLINE} 或消失时清除，避免长期占用内存。
     */
    private final Map<String, Long> negotiatingSince = new ConcurrentHashMap<>();

    /**
     * 心跳超时巡检（间隔走运行时配置，故不能用 {@code @Scheduled(fixedDelayString)} 固定）。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void scanOfflineDevices() {
        if (!due("offline-scan", config.getLong(ConfigKeys.DEVICE_OFFLINE_SCAN_INTERVAL_MS))) {
            return;
        }
        try {
            /* 阈值必须从 config 实时读，不能用 props 里的 yml 快照：device.offline-seconds 界面可热更新，
               读 props 会表现为「保存成功但阈值没变」。 */
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
     * SDK 直连设备（大华等）在线探测。
     *
     * <p><b>为什么必须有这一步</b>：这类设备不发 SIP 心跳，
     * {@link #scanOfflineDevices} 的「心跳超时」判据对它们完全不适用 ——
     * 已经把它们的排除逻辑放在 {@code DeviceService#offlineSilentDevices} 里，
     * 但排除之后它们就<b>再没有任何机制更新在线状态</b>。
     * 结果是：设备明明在线，界面却一直是接入那一刻的状态；
     * 设备真的断电了，界面也不会变。
     *
     * <p>间隔取 60 秒：SDK 登录 + 登出是毫秒级操作，但设备端的登录会话数有限，
     * 探得太勤会挤掉正常业务的会话。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void probeSdkDevices() {
        if (!due("sdk-probe", SDK_PROBE_INTERVAL_MS)) {
            return;
        }
        java.util.List<String> ids;
        try {
            ids = provisionService.sdkDeviceIds();
        } catch (Exception e) {
            log.warn("SDK 设备巡检读取设备列表失败: {}", e.getMessage());
            return;
        }
        int online = 0;
        for (String id : ids) {
            try {
                if (provisionService.probeOnly(id)) {
                    online++;
                }
            } catch (Exception e) {
                // 单台设备探测失败不能影响其余设备
                log.debug("SDK 设备探测失败: {} {}", id, e.toString());
            }
        }
        if (!ids.isEmpty()) {
            log.debug("SDK 设备巡检完成：{} / {} 在线", online, ids.size());
        }
    }

    /** SDK 设备探测间隔（毫秒）：60 秒，见 {@link #probeSdkDevices()}。 */
    private static final long SDK_PROBE_INTERVAL_MS = 60_000L;

    /**
     * 系统指标采样（默认每 3 秒，与前端轮询间隔一致）。
     * 必须由服务端定时采：历史曲线要「过去 3 分钟」，靠前端请求驱动的话第一个开页面的人只能看到一个点。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.metrics-sample-interval-ms:3000}")
    public void sampleSystemMetrics() {
        systemMetricsService.sample();
    }

    /**
     * 目录分页批次回收（默认每 30 秒）：设备少报 {@code SumNum}（报 3 实际只发 2 帧）后
     * 再无帧到达，批次没人收口会永久占内存，这里定期扫掉超时批次。
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
     * 级联上行链路维护（每个 tick 都跑，内部自己限流）。
     * 不用 {@code due(...)} 限流：状态机依赖当前时刻与上次成功时间，粗粒度的「每 N 秒跑一次」
     * 会把重注册窗口推后，上级眼里出现离线空档。
     * {@code tick()} 每次只处理一条链路：注册同步阻塞最长 5 秒，十条不可达的上级会占住调度线程 50 秒。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void maintainCascadeLinks() {
        try {
            cascadeLinkService.tick();
        } catch (Exception e) {
            log.warn("级联链路维护失败: {}", e.getMessage());
        }
    }

    /**
     * 状态变更日志过期清理（每小时，保留 30 天）。
     * {@code gb_status_log} 每个心跳超时 / 重连都写一条（实测 10 天 1 万条），此前没有任何清理路径；
     * 它参与 {@code ORDER BY create_time DESC} 的分页查询，表越大翻页越慢。
     * 保留期比操作日志长：状态变更是排查「某天设备为什么掉线」的关键证据。
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
     * 这三张表此前完全没有清理路径：移动侦测报警每天上千条，车载 / 单兵 GPS 一天数百到上万条，
     * 设备录像索引每次检索都写，跑几个月就能把库撑到几十 GB，且都参与按时间倒序的分页。
     * 保留期：报警按季度复盘，位置一周足够回溯，设备录像索引只是缓存所以最短。
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
     * 录像磁盘清理（默认每 10 分钟，启动 2 分钟后首跑）：先按保留天数删非重要录像，
     * 再按磁盘水位删最老的（跳过 important 与最近 {@link ConfigKeys#RECORD_KEEP_MIN_DAYS} 天）。
     * 必须存在：单通道约 8GB/天，服务器只有 30G 盘，盘满后 ZLM 分片与同盘的 PostgreSQL 都可能写失败。
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
     * 只产生内存信号，绝不改用户配置：不写库、不改 {@code cloud_record} / {@code on_demand}，
     * 只把此刻计划判定应录的通道集合交给 {@link ChannelRecordPolicy#replacePlanActive}。
     * 早先直接改这两个字段，后果是用户开关被计划反复覆盖，且计划把 {@code on_demand} 设为 false 后
     * 空闲流永不被回收（见 {@code StreamService#channelsRequiringStream}），设备一直被拉着推流。
     * 「是否录像」= 用户开关 ∪ 计划信号，两者独立。
     * 多个计划包含同一通道取并集（白班 8-18 + 夜班 20-6 期望两段都录），逻辑在 {@link RecordPlanService#scopeAt}。
     * 只决策不拉流：开录由 {@link #keepCloudRecordStreams()} 负责，避免两个任务同时发 INVITE 打架。
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
     * 后台下载收尾巡检（每 5 秒）。
     * 用户点完下载通常会关掉页面，没有前端轮询 {@code download/progress}；
     * 若收尾只在轮询里做，ZLM 会一直录到 {@code max_second} 上限，任务永远拿不到文件路径。
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void finishDownloads() {
        if (!due("download-finish", 5_000L)) {
            return;
        }
        try {
            downloadService.tick();
        } catch (Exception e) {
            log.warn("后台下载收尾巡检失败: {}", e.getMessage());
        }
    }

    /**
     * 自动录像对账（默认每 60 秒）：把「已结束却未落库」的录像补归档。
     * 归档挂在「流下线」事件上，但事件可能丢 —— 平台重启（ZLM 索引清空）、设备拔线超时（直接 BYE 超时）。
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
     * 流只在用户主动点播时才建立，平台重启后也全清，不保活就表现为「开了云端录像却什么都没发生」。
     * 会话 {@code ONLINE} 时直接跳过；{@code NEGOTIATING} 未超 {@link #STUCK_RETRY_MS} 也跳过，超时才重建一次。
     * 必须退避：{@code startLive} 的复用判断要求「ZLM 确实在收 RTP」，设备不推流时每轮都会
     * 关旧会话 + 重新 INVITE（实测每 30 秒一次 BYE + INVITE、SSRC 逐次递增而录像一段都没生成）。
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
        // 反向对账：正在录但此刻不该录的通道停录并归档。
        // 必须集中在这一处：计划退出只更新内存信号，两处都停会撞车。
        stopRecordingsNotNeeded();
    }

    /**
     * 停掉「正在录但此刻不该录」的通道（计划退出时段、用户关开关、计划删除 / 停用）。
     * 拿 {@code recordPolicy.enabledChannels()} 与「实际在录」比对即可，不必关心是哪个原因；
     * 停录会顺带归档（{@code stopCloudRecord} 内部落库），最后一段不会丢。
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
     * {@code on_mk_media_publish} 只在流首次注册时触发一次 —— 实测 SSRC 映射恢复完成于 17:53:06.5，
     * 而设备推流回调在 17:53:05.1（早 1.4 秒），错过就是永久错过。
     * 周期短（默认 15 秒）是因为孤儿流既占带宽又让用户看不到画面。
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
     * 不能只靠 ZLM 的 {@code on_mk_media_no_reader}：每个媒体源只触发一次，且只在读者数由非零降为零时触发，
     * 平台重启后回调在会话重建前已触发（实测相差 2 秒）、{@link #adoptOrphanStreams()} 纳管时流早已注册，都不补发。
     * 与孤儿流巡检成闭环：前者让流可见，本任务在没人看时关掉，两者都以 ZLM 实际媒体源为准。
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
