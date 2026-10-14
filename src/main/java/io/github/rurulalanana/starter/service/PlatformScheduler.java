package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台定时任务。
 *
 * <p>当前只做一件事：把心跳超时的设备标记为离线。
 * 设备断电 / 断网时不会发注销，只能靠心跳超时判定。
 *
 * <p>通道只由设备上报的目录应答产生，这里不做任何兜底登记。
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
}
