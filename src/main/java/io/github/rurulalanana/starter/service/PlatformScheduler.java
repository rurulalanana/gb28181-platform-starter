package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台定时任务。
 *
 * <p>当前做五件事：心跳超时设备标记离线；操作日志过期清理；系统指标采样；
 * 自动录像对账；云端录像保活。
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
    private final Gb28181Properties props;

    /** 操作日志保留天数（默认 7 天）。 */
    @Value("${gb28181.starter.oplog-retain-days:7}")
    private int oplogRetainDays;

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

    /**
     * 操作日志过期清理：启动 1 分钟后首跑，之后每小时一次。
     *
     * <p>保留天数由 {@code gb28181.starter.oplog-retain-days} 控制（默认 7 天）。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.oplog-purge-interval-ms:3600000}",
            initialDelayString = "${gb28181.starter.oplog-purge-initial-delay-ms:60000}")
    public void purgeOperationLogs() {
        try {
            int deleted = operationLogService.purgeExpired(oplogRetainDays);
            if (deleted > 0) {
                log.info("操作日志清理：{} 条过期记录已删除（保留 {} 天）", deleted, oplogRetainDays);
            }
        } catch (Exception e) {
            log.warn("操作日志清理失败: {}", e.getMessage());
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
    @Scheduled(fixedDelayString = "${gb28181.starter.record-reconcile-interval-ms:60000}",
            initialDelayString = "${gb28181.starter.record-reconcile-initial-delay-ms:30000}")
    public void reconcileRecordings() {
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
     * <p><b>不重复拉流</b>：{@code startLive} 内部会复用可用的活会话，
     * 已在线时是空操作（不重新 INVITE）。所以本任务的成本只是每周期一次
     * 内存查表 + 必要的探测，不产生多余信令。
     *
     * <p><b>失败不重试轰炸设备</b>：通道离线、设备无响应等都会抛异常，
     * 这里只记 DEBUG 并跳过，等下一个周期自然重试。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.record-keepalive-interval-ms:30000}",
            initialDelayString = "${gb28181.starter.record-keepalive-initial-delay-ms:45000}")
    public void keepCloudRecordStreams() {
        if (!recordPolicy.isLoaded()) {
            return;
        }
        for (String channelId : recordPolicy.enabledChannels()) {
            try {
                GbChannel ch = deviceService.findChannel(channelId);
                if (ch == null || !ch.isOnline()) {
                    continue;
                }
                // 已有可复用会话时内部直接返回，不会重复 INVITE
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
    @Scheduled(fixedDelayString = "${gb28181.starter.idle-reap-interval-ms:20000}",
            initialDelayString = "${gb28181.starter.idle-reap-initial-delay-ms:40000}")
    public void reapIdleStreams() {
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
