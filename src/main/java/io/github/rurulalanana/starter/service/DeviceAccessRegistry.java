package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 接入方式 → 实现的注册表。
 *
 * <h3>为什么放在平台侧，而不是 sdk-dahua 模块里</h3>
 * 契约（{@code deviceaccess} 包）在 sdk-dahua 里，是<b>实现无关</b>的；
 * 而「怎么把实现找出来」是 Spring 容器的事，属于平台职责。
 * 把注册表放进实现模块，等于要求平台依赖某个具体实现才能调度 ——
 * 将来接海康时就会变成「为了注册海康，还得改大华模块」。
 *
 * <h3>为什么用 ObjectProvider 延迟取</h3>
 * 大华 provider 的静态初始化会加载原生库（libdhnetsdk.so）。
 * 若在构造期注入 {@code List<DeviceAccessProvider>}，容器启动时就会
 * 实例化所有 provider —— 一旦 SDK 没装好，<b>整个平台起不来</b>，
 * 连国标链路都不可用。用 {@link ObjectProvider} 按需解析，
 * SDK 缺失只影响大华设备。
 *
 * <h3>为什么不在这里查数据库判类型</h3>
 * 调用方本来就要查设备（否则拿不到 IP/端口/凭据），顺手把 {@code source}
 * 传进来即可。这里再查一次会引入 {@code DeviceService} → 注册表 →
 * {@code DeviceService} 的循环依赖。
 */
@Slf4j
@Component
public class DeviceAccessRegistry {

    private final ObjectProvider<DeviceAccessProvider> providers;
    /** 惰性构建的映射表：首次使用时解析，之后直接用。 */
    private volatile Map<DeviceKind, DeviceAccessProvider> index;

    public DeviceAccessRegistry(ObjectProvider<DeviceAccessProvider> providers) {
        this.providers = providers;
    }

    /**
     * 取某接入方式的实现。
     *
     * @return 国标、或对应 provider 未装（SDK 缺失）时返回 {@link Optional#empty()}
     */
    public Optional<DeviceAccessProvider> provider(DeviceKind kind) {
        if (kind == null || kind == DeviceKind.GB28181) {
            // 国标走平台原生链路，没有 SPI 实现
            return Optional.empty();
        }
        return Optional.ofNullable(index().get(kind));
    }

    /** 该接入方式当前是否可用（供接口层给出明确提示）。 */
    public boolean available(DeviceKind kind) {
        return provider(kind).isPresent();
    }

    /**
     * 取实现，否则给出可操作的错误信息。
     *
     * <p>错误信息必须说清「缺什么」，否则运维只会看到一句
     * 「不支持该设备类型」，无从下手。
     */
    public DeviceAccessProvider require(DeviceKind kind) {
        return provider(kind).orElseThrow(() -> new IllegalStateException(
                "设备接入方式 " + kind.code() + " 不可用：未安装对应的 SDK 模块"
                        + "（大华需要 netsdk 与原生库，见 docs/大华SDK接入.md）"));
    }

    private Map<DeviceKind, DeviceAccessProvider> index() {
        Map<DeviceKind, DeviceAccessProvider> m = index;
        if (m != null) {
            return m;
        }
        synchronized (this) {
            if (index != null) {
                return index;
            }
            Map<DeviceKind, DeviceAccessProvider> built = new EnumMap<>(DeviceKind.class);
            List<DeviceAccessProvider> list;
            try {
                list = providers.orderedStream().toList();
            } catch (Exception e) {
                // 某个 provider 的静态初始化失败（典型是原生库缺失）不应拖垮平台
                log.warn("解析设备接入实现失败，按「无额外接入方式」继续：{}", e.toString());
                list = List.of();
            }
            for (DeviceAccessProvider p : list) {
                if (p == null || p.kind() == null) {
                    continue;
                }
                if (built.putIfAbsent(p.kind(), p) != null) {
                    log.warn("接入方式 {} 存在多个实现，已保留先注册的 {}",
                            p.kind().code(), built.get(p.kind()).getClass().getSimpleName());
                }
            }
            if (!built.isEmpty()) {
                log.info("已注册的设备接入方式: {}", built.keySet());
            }
            index = built;
            return built;
        }
    }
}
