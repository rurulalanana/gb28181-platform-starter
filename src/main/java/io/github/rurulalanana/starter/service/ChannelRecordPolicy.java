package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道级云端录像开关（{@code gb_channel.cloud_record}）的内存镜像。
 *
 * <p><b>为什么需要镜像</b>：这个开关要在<b>流上线回调</b>里读，而该回调运行在
 * ZLM 的原生事件线程上，绝不能在里面查数据库 —— 一次查询几百毫秒，
 * 会把 ZLM 的事件派发拖住，连带影响所有流的注册与注销。
 * 因此启动时把开关一次性载入内存，之后只读内存。
 *
 * <p>写入路径（用户拨动通道列表的开关）同时更新库与镜像，两者保持一致。
 *
 * <p><b>与 {@code auto-record} 的区别</b>：{@code auto-record} 是全局开关
 * （{@code gb28181.media.auto-record}，默认 false），控制「所有流都自动录」；
 * 本类管的是<b>逐通道</b>的开关，是用户在通道列表里逐个打开的。
 * 两者是「或」的关系：任一为真即录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRecordPolicy {

    private final DeviceService deviceService;

    /** 已开启云端录像的通道编码。 */
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();

    /** 是否已完成首次加载（未加载时不应据此判定「不录」）。 */
    private volatile boolean loaded;

    /**
     * 从数据库载入全部开关。
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

    /** 该通道是否要求云端录像。 */
    public boolean isEnabled(String channelId) {
        return channelId != null && enabled.contains(channelId);
    }

    /**
     * 当前已开启云端录像的全部通道编码（快照）。
     *
     * <p>返回副本而不是内部集合：调用方（定时任务）会遍历并可能触发
     * 拉流/开录，耗时较长，直接遍历 {@link ConcurrentHashMap} 的视图
     * 会让并发写（用户拨动开关）的语义不明确。副本是一次性的、稳定的。
     */
    public Set<String> enabledChannels() {
        return Set.copyOf(enabled);
    }

    /** 用户拨动开关时同步内存镜像。 */
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

    /** 是否已载入（供健康检查/诊断用）。 */
    public boolean isLoaded() {
        return loaded;
    }
}
