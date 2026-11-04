package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「某通道此刻是否应该录云端录像」的唯一判定入口。
 *
 * <p>必须用内存镜像：判定在 ZLM 原生事件线程的流上线回调里读，查库一次几百毫秒会拖住
 * 所有流的注册与注销，故启动时载入、之后只读内存。
 *
 * <p>两个独立信号取并集：用户手动开关（{@code gb_channel.cloud_record}，持久化，
 * 只有用户拨开关才会变）与录像计划（{@link #planActive}，纯内存，每 30 秒重算）。
 * 语义即「手动开启 = 全天录、忽略计划；手动关闭 = 走计划时段」。
 *
 * <p>计划不许写库：早先直接改 {@code cloud_record} 与 {@code on_demand}，会反复覆盖用户的开关，
 * 且 {@code on_demand=false} 让空闲流永不回收。
 * 与 {@code gb28181.media.auto-record}（全局「所有流都自动录」）不同，本类管逐通道判定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRecordPolicy {

    private final DeviceService deviceService;

    /** 用户手动开启云端录像的通道（{@code gb_channel.cloud_record}）。除用户拨开关外任何地方不许改。 */
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();

    /**
     * 录像计划当前判定「应在录制」的通道（纯内存，不落库）。
     * 计划是「随时间变化的运行时判断」，写回库会污染用户的持久配置；读取时与 {@link #enabled} 取并集。
     */
    private final Set<String> planActive = ConcurrentHashMap.newKeySet();

    /** 是否已完成首次加载（未加载时不应据此判定「不录」）。 */
    private volatile boolean loaded;

    /**
     * 从数据库载入用户的全部手动开关（{@code StreamEventBridge} 挂载事件桥时调用）。
     * 载入前 {@link #isEnabled} 一律返回 false，避免数据未就绪时误判。
     */
    public void reload() {
        try {
            Set<String> next = ConcurrentHashMap.newKeySet();
            for (GbChannel ch : deviceService.allChannels()) {
                if (Boolean.TRUE.equals(ch.getCloudRecord()) && ch.getChannelId() != null) {
                    next.add(ch.getChannelId());
                }
            }
            enabled.clear();
            enabled.addAll(next);
            loaded = true;
            if (!next.isEmpty()) {
                log.info("已载入 {} 个通道的云端录像开关", next.size());
            }
        } catch (Exception e) {
            log.warn("载入通道云端录像开关失败（本次不启用自动录像）: {}", e.getMessage());
        }
    }

    /**
     * 该通道此刻是否应该录制：{@code 手动开关 || 计划}。手动开启时短路，
     * 所以计划退出时段不会停掉手动开的通道 —— 这是需要的语义，不是巧合。
     */
    public boolean isEnabled(String channelId) {
        if (channelId == null) {
            return false;
        }
        // 手动开启优先：直接返回，不受计划影响
        if (enabled.contains(channelId)) {
            return true;
        }
        return planActive.contains(channelId);
    }

    /** 该通道此刻是否仅因录像计划而录制（手动未开启），用于界面区分。 */
    public boolean isPlanOnly(String channelId) {
        return channelId != null
                && !enabled.contains(channelId)
                && planActive.contains(channelId);
    }

    /** 仅「用户手动开启」的通道（不含计划），用于界面区分与诊断。 */
    public Set<String> manualEnabledChannels() {
        return Set.copyOf(enabled);
    }

    /** 仅「录像计划判定应录」的通道。 */
    public Set<String> planActiveChannels() {
        return Set.copyOf(planActive);
    }

    /**
     * 当前应录制的全部通道（手动 ∪ 计划）快照。返回副本而非内部集合：
     * 调用方会遍历并触发拉流 / 开录，直接遍历会让并发写（用户拨开关）的语义不明确。
     */
    public Set<String> enabledChannels() {
        Set<String> all = new java.util.HashSet<>(enabled);
        all.addAll(planActive);
        return Set.copyOf(all);
    }

    /** 用户拨动开关时同步内存镜像（同时由接口写库）。 */
    public void set(String channelId, boolean on) {
        if (channelId == null) {
            return;
        }
        if (on) {
            enabled.add(channelId);
        } else {
            enabled.remove(channelId);
        }
    }

    /**
     * 用计划的判定结果整体替换「计划生效通道」，只动内存，不碰数据库与 {@code on_demand}。
     *
     * @param active 此刻计划判定应录的通道；{@code null} 视为空集
     * @return 相比替换前新增的通道（可用于立即触发开录）
     */
    public Set<String> replacePlanActive(Set<String> active) {
        Set<String> next = active == null ? Set.of() : Set.copyOf(active);
        Set<String> before = Set.copyOf(planActive);
        planActive.clear();
        planActive.addAll(next);
        // 返回「新进入」的通道，调用方可以据此立即触发开录而不是等下一轮保活
        Set<String> added = new java.util.HashSet<>(next);
        added.removeAll(before);
        return added;
    }

    /** 是否已载入（供健康检查/诊断用）。 */
    public boolean isLoaded() {
        return loaded;
    }
}
