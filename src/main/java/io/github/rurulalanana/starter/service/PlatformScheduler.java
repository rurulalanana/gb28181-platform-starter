package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台定时任务。
 *
 * <p>当前做三件事：心跳超时设备标记离线；操作日志过期清理；系统指标采样。
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
}
