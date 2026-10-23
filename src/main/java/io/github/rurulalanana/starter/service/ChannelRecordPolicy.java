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
 * <p><b>为什么需要内存镜像</b>：这个判断要在<b>流上线回调</b>里读，而该回调运行在
 * ZLM 的原生事件线程上，绝不能在里面查数据库 —— 一次查询几百毫秒，
 * 会把 ZLM 的事件派发拖住，连带影响所有流的注册与注销。
 * 因此启动时把开关一次性载入内存，之后只读内存。
 *
 * <h3>两个独立信号，取并集</h3>
 *
 * <ol>
 *   <li><b>用户手动开关</b>（{@link #enabled}）—— 来自 {@code gb_channel.cloud_record}，
 *       持久化，<b>只有用户拨开关才会变</b>；</li>
 *   <li><b>录像计划</b>（{@link #planActive}）—— 纯内存，每 30 秒由计划引擎重算。</li>
 * </ol>
 *
 * <h3>判定规则（一句话）</h3>
 *
 * <p><b>手动开启 = 全天录制、完全忽略计划；手动关闭 = 走计划时段。</b>
 *
 * <pre>
 *   cloud_record = true   → 录（计划是什么都不看，也不受时段影响）
 *   cloud_record = false  → 只在「录像计划的时段内」录
 * </pre>
 *
 * <p>这就是下面两个信号取<b>并集</b>的效果，也是用户期望的语义：
 * 手动开是「我就要一直录」，属于强意图；计划是「我没特别指定时按时间表来」，
 * 属于兜底。两者天然是「或」的关系，不需要额外的优先级字段。
 *
 * <p><b>计划不许写数据库</b>：早先的实现让计划直接改
 * {@code gb_channel.cloud_record} 与 {@code on_demand}，后果是
 * 用户设的状态被计划反复覆盖（界面上看到状态自己变，无法维护），
 * 且 {@code on_demand=false} 会让空闲流永不回收、白占带宽。
 * 现在计划只动内存，用户的持久配置完全由用户掌握。
 *
 * <p><b>与 {@code auto-record} 的区别</b>：{@code auto-record} 是全局开关
 * （{@code gb28181.media.auto-record}，默认 false），控制「所有流都自动录」；
 * 本类管的是<b>逐通道</b>的判定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRecordPolicy {

    private final DeviceService deviceService;

    /**
     * 用户<b>手动</b>开启云端录像的通道（{@code gb_channel.cloud_record}）。
     *
     * <p>这是用户的持久化选择，<b>除了用户拨开关，任何地方都不许改它</b>。
     * 录像计划不碰这个字段（见 {@link #planActive}）。
     */
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();

    /**
     * 录像计划当前判定「应在录制」的通道（纯内存，不落库）。
     *
     * <p><b>为什么不写回数据库</b>：早先的实现让计划直接改
     * {@code gb_channel.cloud_record} 和 {@code on_demand}，结果是
     * 用户手动设的开关会被计划反复覆盖 —— 界面上看到状态自己变，
     * 而且 {@code on_demand=false} 会让空闲流永远不被回收（白占带宽）。
     * 计划是「随时间变化的运行时判断」，不该污染用户的持久配置。
     *
     * <p>因此计划只更新这个内存集合，读取时与 {@link #enabled} 取<b>并集</b>。
     */
    private final Set<String> planActive = ConcurrentHashMap.newKeySet();

    /** 是否已完成首次加载（未加载时不应据此判定「不录」）。 */
    private volatile boolean loaded;

    /**
     * 从数据库载入用户的全部手动开关。
     *
     * <p>由 {@code StreamEventBridge} 在挂载事件桥时调用（此时设备数据已就绪）。
     * 载入前 {@link #isEnabled} 一律返回 false，避免在数据未就绪时误判。
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
     * 该通道此刻是否应该录制。
     *
     * <p><b>规则</b>：
     * <ul>
     *   <li>{@code cloud_record=true}（手动开启）→ <b>总是录</b>，
     *       计划状态完全忽略（用户意图是「全天录」）；</li>
     *   <li>{@code cloud_record=false} → 只在录像计划时段内录。</li>
     * </ul>
     *
     * <p>实现上就是 {@code manual || plan}：手动开启时短路，连 plan 都不用看。
     * 因此计划退出时段<b>不会</b>停掉手动开启的通道 —— 这是需要的语义，
     * 不是巧合。
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

    /**
     * 该通道此刻是否<b>仅因录像计划</b>而录制（手动未开启）。
     *
     * <p>用于界面标注「这段录像是计划触发的」，让运维能区分
     * 「我手动开的」与「计划自动开的」。
     */
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
     * 当前应录制的全部通道（手动 ∪ 计划），快照。
     *
     * <p>返回副本而不是内部集合：调用方（定时任务）会遍历并可能触发
     * 拉流/开录，耗时较长，直接遍历 {@link ConcurrentHashMap} 的视图
     * 会让并发写（用户拨动开关）的语义不明确。副本是一次性的、稳定的。
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
     * 用录像计划的判定结果整体替换「计划生效通道」集合。
     *
     * <p>只动内存，<b>不碰数据库、不碰 {@code on_demand}</b>。
     *
     * @param active 此刻计划判定应录的通道编码；{@code null} 视为空集
     * @return 与替换前相比新增的通道（用于日志/触发开录）
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
