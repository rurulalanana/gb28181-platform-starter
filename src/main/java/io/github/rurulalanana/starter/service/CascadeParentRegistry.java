package io.github.rurulalanana.starter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上级平台编码登记表。
 *
 * <p><b>为什么需要独立一个类</b>：媒体模块要在收到 INVITE 时立刻判断
 * 「这是不是我的上级平台」，而上级编码存在数据库（{@code gb_cascade.parent_id}）。
 * 让媒体模块直接查库既造成模块间耦合，也会在信令线程上引入 DB 延迟。
 *
 * <p>更关键的是<b>初始化顺序</b>：媒体平台 bean 构造得很早（SIP 栈要立刻起），
 * 那时 {@link CascadeLinkService} 还没读库。如果让它直接依赖 CascadeLinkService
 * 就会形成 bean 循环（CascadeLinkService → Gb28181SipClientProvider →
 * Gb28181MediaPlatform → …）。所以这里放一个<b>无依赖</b>的纯内存集合：
 * 平台启动时把它的判定方法交给媒体层，之后 CascadeLinkService 按需刷新内容。
 *
 * <p>线程安全：用 {@link ConcurrentHashMap#newKeySet()}，写少读多且在信令线程上读。
 */
@Slf4j
@Component
public class CascadeParentRegistry {

    private final Set<String> parentIds = ConcurrentHashMap.newKeySet();

    /**
     * 这个编码是不是已配置的上级平台。
     *
     * <p>供媒体层作为 {@code Predicate} 使用 —— 必须<b>极快且不阻塞</b>，
     * 它在 SIP 协议栈线程上被每个 INVITE 调用一次。
     */
    public boolean contains(String platformId) {
        return platformId != null && parentIds.contains(platformId);
    }

    /** 全量替换（配置变更后调用）。 */
    public void replace(Set<String> ids) {
        Set<String> next = new java.util.HashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.trim().isEmpty()) {
                    next.add(id.trim());
                }
            }
        }
        /*
         * 内容没变就不动、也不打日志。
         *
         * <p>本方法每 5 秒被调度器调一次（tick 开头对齐登记表），
         * 每次都 clear+add 会产生两件坏事：
         * 一是日志被「登记表已更新」刷满（每 5 秒一条，一天一万七千条）；
         * 二是 clear 与 add 之间有一个短暂的空窗 ——
         * 恰好此时上级来点播，就会被判成「不是上级」而走错分支。
         * 所以先算差异，相同就直接返回。
         */
        if (next.equals(parentIds)) {
            return;
        }
        parentIds.clear();
        parentIds.addAll(next);
        log.info("级联上级平台登记表已更新：{} 个（{}）",
                parentIds.size(), String.join(", ", parentIds));
    }

    /** 当前已登记的上级编码（只读快照）。 */
    public Set<String> snapshot() {
        return Collections.unmodifiableSet(new java.util.HashSet<>(parentIds));
    }
}
