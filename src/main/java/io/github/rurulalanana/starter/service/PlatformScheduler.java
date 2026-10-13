package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台定时任务。
 *
 * <p>当前只做两件事：把心跳超时的设备标记为离线、为无通道的在线设备兜底登记通道。
 * 设备断电 / 断网时不会发注销，只能靠心跳超时判定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformScheduler {

    private final DeviceService deviceService;
    private final Gb28181Properties props;

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
     * 通道兜底补齐，默认每 120 秒一次。
     *
     * <p>部分 IPC/NVR 的 Catalog 应答不含子通道，导致通道表为空、前端看不到通道。
     * 这里周期性为「在线但无任何通道」的设备登记一条自身通道。
     */
    @Scheduled(fixedDelayString = "${gb28181.starter.channel-backfill-interval-ms:120000}")
    public void backfillSelfChannels() {
        try {
            for (var device : deviceService.allDevices()) {
                if (!Boolean.TRUE.equals(device.getOnline())) {
                    continue;
                }
                if (deviceService.ensureSelfChannel(device.getDeviceId())) {
                    log.info("兜底补齐：设备 {} 无通道记录，已登记自身为通道", device.getDeviceId());
                }
            }
        } catch (Exception e) {
            log.warn("通道兜底补齐失败: {}", e.getMessage());
        }
    }
}
