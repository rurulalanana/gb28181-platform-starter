package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.service.DeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按设备接入方式选 {@link DeviceControl} 实现。
 *
 * <h3>核心约定：永不返回 {@code null}</h3>
 * 改造前 {@code providerOf(...)} 用 {@code null} 表示「国标」，31 个调用点都得记住这个暗号，
 * 还得防「两次调用之间设备被删」的 NPE。这里改成<b>永远返回一个实现</b>：
 * 国标有 {@link Gb28181DeviceControl}，厂商有各自的 bean，
 * 未知接入方式保守地按国标处理（与 {@code DeviceKind.of} 的兜底口径一致）。
 *
 * <p>因此调用方的形状从
 * <pre>
 *   if (providerOf(x) != null) { 厂商逻辑(); return; }
 *   国标逻辑();
 * </pre>
 * 变成
 * <pre>
 *   resolver.of(channelId).reboot(channelId);
 * </pre>
 *
 * <h3>为什么在入口处选实现，而不是每个方法里判</h3>
 * 选一次实现，32 个方法各自被调用一次；在每个方法里判就要判 32 次，
 * 而且新增命令时必然有人漏判（改造前 {@code ptzPrecise} / {@code subscribe}
 * 就是因为没有分支，把 SDK 设备当成国标往 SIP 里发）。
 */
@Slf4j
@Component
public class DeviceControlResolver {

    private final DeviceService deviceService;
    private final Map<DeviceKind, DeviceControl> index = new EnumMap<>(DeviceKind.class);
    /** 兜底实现：任何未知接入方式都按国标处理。 */
    private final DeviceControl fallback;

    /**
     * @param controls 容器里全部 {@link DeviceControl} 实现。
     *                 用 {@code List} 注入而不是逐个声明，是为了加厂商时
     *                 <b>不用改本类</b>——新增一个 {@code HikvisionDeviceControl}
     *                 {@code @Component} 就自动生效。
     */
    public DeviceControlResolver(DeviceService deviceService, List<DeviceControl> controls) {
        this.deviceService = deviceService;
        for (DeviceControl c : controls) {
            if (c == null || c.kind() == null) {
                continue;
            }
            if (index.putIfAbsent(c.kind(), c) != null) {
                log.warn("接入方式 {} 存在多个控制实现，已保留先注册的 {}",
                        c.kind().code(), index.get(c.kind()).getClass().getSimpleName());
            }
        }
        DeviceControl gb = index.get(DeviceKind.GB28181);
        if (gb == null) {
            // 国标是主链路，缺了它所有控制命令都会失效，必须在启动时就暴露出来
            throw new IllegalStateException(
                    "缺少国标控制实现 Gb28181DeviceControl，平台无法下发任何控制命令");
        }
        this.fallback = gb;
        log.info("已注册的设备控制实现: {}", index.keySet());
    }

    /**
     * 取该通道所属设备的控制实现。
     *
     * @param channelId 通道编码或设备编码（两种都支持，与旧行为一致）
     * @return 永不为 {@code null}
     */
    public DeviceControl of(String channelId) {
        return ofDeviceId(deviceIdOf(channelId));
    }

    /**
     * 取指定设备编码的控制实现。
     *
     * @return 永不为 {@code null}
     */
    public DeviceControl ofDeviceId(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            // 设备不在库里（例如有人直接拿通道编码调接口）——按国标处理，
            // 让国标实现去报「设备未注册」，比在这里编一句「设备不存在」更接近事实
            return fallback;
        }
        return index.getOrDefault(DeviceKind.of(device.getSource()), fallback);
    }

    /** 该设备是否走厂商 SDK 链路（用于日志与少量仍需按协议区分的地方）。 */
    public boolean isSdkDevice(String channelId) {
        return of(channelId).kind() != DeviceKind.GB28181;
    }

    /**
     * 通道编码 → 设备编码。
     *
     * <p>与 {@code ControlService#target} 的回退口径一致：查不到所属设备的通道
     * 就认为「传进来的其实就是设备编码」（直连摄像机二者相同）。
     */
    private String deviceIdOf(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String owner = deviceService.deviceIdOfChannel(channelId);
        return owner == null ? channelId : owner;
    }
}
