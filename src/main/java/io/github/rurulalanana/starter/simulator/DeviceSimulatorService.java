package io.github.rurulalanana.starter.simulator;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模拟设备管理：启动/停止模拟设备，定期心跳。
 */
@Slf4j
@Service
public class DeviceSimulatorService {

    private final Gb28181Properties props;
    private final Gb28181MediaPlatform platform;
    private final Map<String, SimulatedDevice> devices = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public DeviceSimulatorService(Gb28181Properties props, Gb28181MediaPlatform platform) {
        this.props = props;
        this.platform = platform;
    }

    @PostConstruct
    public void init() {
        Gb28181Properties.Simulator cfg = props.getSimulator();
        if (!cfg.isEnabled()) {
            log.info("设备模拟器未启用（gb28181.simulator.enabled=false）");
            return;
        }
        for (int i = 0; i < Math.max(0, cfg.getCount()); i++) {
            String id = cfg.getDeviceIdPrefix();
            try {
                addDevice(id, "模拟通道 " + (i + 1), cfg.isPushTestMedia());
            } catch (IOException e) {
                log.warn("创建模拟设备失败: {}", id);
            }
        }
        // 周期心跳，保持设备在线
        scheduler.scheduleAtFixedRate(() -> {
            String ip = props.getSipHost();
            int port = props.getSipPort();
            for (SimulatedDevice d : devices.values()) {
                d.keepalive("127.0.0.1", port);
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    /** 新增一个模拟设备并注册到平台。 */
    public synchronized SimulatedDevice addDevice(String deviceId, String name, boolean pushMedia) throws IOException {
        SimulatedDevice existing = devices.get(deviceId);
        if (existing != null) {
            return existing;
        }
        SimulatedDevice device = new SimulatedDevice(deviceId, name);
        Gb28181Properties.Simulator cfg = props.getSimulator();
        device.setMediaConfig(new SimulatedDevice.MediaConfig(
                pushMedia, cfg.getFfmpeg(), cfg.getTestSource()));
        devices.put(deviceId, device);
        // 平台实际监听 0.0.0.0 时，设备侧回连 127.0.0.1
        device.start("127.0.0.1", props.getSipPort());
        log.info("模拟设备已创建并注册: {}（{}）", deviceId, device.getAddress());
        return device;
    }

    /** 移除并停止一个模拟设备。 */
    public synchronized void removeDevice(String deviceId) {
        SimulatedDevice device = devices.remove(deviceId);
        if (device != null) {
            device.stop();
            log.info("模拟设备已移除: {}", deviceId);
        }
    }

    public Collection<SimulatedDevice> list() {
        return new ArrayList<>(devices.values());
    }

    public SimulatedDevice get(String deviceId) {
        return devices.get(deviceId);
    }

    /** 切换某设备的测试媒体推送。 */
    public void toggleMedia(String deviceId, boolean enable) {
        SimulatedDevice d = devices.get(deviceId);
        if (d == null) {
            return;
        }
        Gb28181Properties.Simulator cfg = props.getSimulator();
        d.setMediaConfig(new SimulatedDevice.MediaConfig(enable, cfg.getFfmpeg(), cfg.getTestSource()));
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        for (SimulatedDevice d : devices.values()) {
            d.stop();
        }
        devices.clear();
    }
}