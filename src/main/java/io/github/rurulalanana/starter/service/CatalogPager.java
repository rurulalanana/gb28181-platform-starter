package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页目录聚合器。GB/T 28181 附录 A.2.4 的 Catalog 应答支持分页：{@code <SumNum>} 是总条数，
 * {@code <DeviceList Num>} 只带一页。实测 LiveGBS 有 3 个通道会连发 3 个 Response，
 * 每帧 {@code SumNum=3} 但只含 1 个 Item；不聚合则每帧都被当全量落库，只留最后一帧的结果。
 *
 * <p>按「设备编码 + SN」分组累积，收齐（已收 &ge; SumNum）或超 {@link #WINDOW_MS} 即收口。
 * 按 SN 分组是为了不让并发查询（定时采集 + 手动刷新）的两批应答混成一份；SN 是 1~9999 循环的，
 * 所以收口即清除。
 */
@Slf4j
@Component
public class CatalogPager {

    /** 聚合窗口（毫秒）：实测下级连发 3 帧不到 1 秒，8 秒足够覆盖跨网抖动又不至于让人干等。 */
    static final long WINDOW_MS = 8_000L;

    /**
     * 收口结果。
     *
     * @param complete 是否已收齐（已收 &ge; {@code SumNum}）；只有收齐才能当全量快照清理旧通道
     * @param sumNum   设备自报总条数，0 表示未提供；带上它才能区分「下级只共享 2 个」与「共享 4 个只收到 2 个」
     * @param removed  被 {@code <Event>DELETE</Event>} 标记的通道，与 {@code items} 互斥（后到的状态覆盖先前的）
     */
    public record Result(List<CatalogItem> items, boolean complete, int sumNum, int frames,
                         List<String> removed) {
    }

    /** 正在聚合的批次，key = {@code deviceId#sn}。 */
    private final Map<String, Batch> pending = new ConcurrentHashMap<>();

    /** 一个聚合批次。 */
    private static final class Batch {
        /** 设备自报的总条数（{@code SumNum}），0 或 null 视为未知。 */
        private final int sumNum;
        private final long startedAt = System.currentTimeMillis();
        /** 已累积的条目，按设备编码去重（标准要求设备编码全局唯一）。 */
        private final Map<String, CatalogItem> items = new LinkedHashMap<>();
        /** 被 {@code <Event>DELETE</Event>} 标记、待删除的通道编码。 */
        private final java.util.LinkedHashSet<String> removed = new java.util.LinkedHashSet<>();
        /**
         * 已处理过的条目标记（{@code 通道编码|类型}），用于计算收口进度。
         * 不能直接用 {@code items.size()}：同一批次先 ADD 后 DELETE 同一通道时净状态恒为 1，
         * 用它判断进度会永远收不齐、只能等超时；完全不去重又会让重传帧把 SumNum 撑爆。
         * 所以净状态（items / removed）与处理计数（seen）必须分开。
         */
        private final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        /** 收到的应答帧数（诊断「单帧多条」与「多帧各一条」两种风格）。 */
        private int frames;

        Batch(int sumNum) {
            this.sumNum = sumNum;
        }
    }

    /**
     * 投递一帧目录应答。
     *
     * @param sn     会话序号，用于区分同一设备的并发查询
     * @param sumNum 本次查询的总条数，未知传 {@code null}
     * @return 已收齐时返回累积结果；未收齐返回 {@code null}
     */
    public Result accept(String deviceId, int sn, Integer sumNum,
                         Collection<CatalogItem> items) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        String key = deviceId + "#" + sn;
        int total = sumNum == null || sumNum < 0 ? 0 : sumNum;

        Batch batch = pending.computeIfAbsent(key, k -> new Batch(total));
        synchronized (batch) {
            batch.frames++;
            if (items != null) {
                for (CatalogItem it : items) {
                    if (it == null || it.getDeviceId() == null || it.getDeviceId().isBlank()) {
                        continue;
                    }
                    boolean del = isDelete(it);
                    // 进度计数：同一 (通道, 变更类型) 只算一次，重复重传不撑大 SumNum
                    if (!batch.seen.add(it.getDeviceId() + "|" + (del ? "DEL" : "UPS"))) {
                        continue;
                    }
                    if (del) {
                        // <Event>DELETE</Event>：当 upsert 处理会把下级删掉的通道加回来，形成幽灵通道
                        batch.items.remove(it.getDeviceId());
                        batch.removed.add(it.getDeviceId());
                    } else {
                        // 后到的 ADD/UPDATE 覆盖先前的 DELETE 状态
                        batch.removed.remove(it.getDeviceId());
                        batch.items.putIfAbsent(it.getDeviceId(), it);
                    }
                }
            }
            // 没有 SumNum 的设备（大量国标 IPC / NVR 不带此字段）无从判断是否还有后续帧，
            // 它们实际都是单帧全量上报，直接收口，不要白等 8 秒。
            if (batch.sumNum <= 0) {
                pending.remove(key);
                return new Result(new ArrayList<>(batch.items.values()), false, 0,
                        batch.frames, new ArrayList<>(batch.removed));
            }
            // 收口条件按「已处理的条目数」算：删除项同样消耗 SumNum 配额
            boolean complete = batch.seen.size() >= batch.sumNum;
            boolean expired = System.currentTimeMillis() - batch.startedAt > WINDOW_MS;
            if (!complete && !expired) {
                log.debug("目录分页累积中: device={} sn={} 已收={}/{}",
                        deviceId, sn, batch.seen.size(), batch.sumNum);
                return null;
            }
            pending.remove(key);
            if (!complete) {
                // 少报通常意味着设备侧过滤（权限 / 离线通道不报），不是错误，但要在日志里留痕
                log.info("目录分页未收齐即收口: device={} sn={} 已收={}/{} 帧数={}",
                        deviceId, sn, batch.seen.size(), batch.sumNum, batch.frames);
            }
            if (!batch.removed.isEmpty()) {
                log.info("目录推送含删除: device={} 待删除通道={}", deviceId, batch.removed);
            }
            if (log.isDebugEnabled()) {
                log.debug("目录分页收口: device={} sn={} 帧数={} 条目={} 删除={} SumNum={} 收齐={}",
                        deviceId, sn, batch.frames, batch.items.size(), batch.removed.size(),
                        batch.sumNum, complete);
            }
            return new Result(new ArrayList<>(batch.items.values()), complete,
                    batch.sumNum, batch.frames, new ArrayList<>(batch.removed));
        }
    }

    /**
     * 判断目录项是否为删除通知：附录 A.2.4 的 {@code <Event>} 取 ADD/UPDATE/DELETE/ON/OFF，
     * 仅在通知类报文中出现；大小写不敏感（实测 LiveGBS、大华两种写法都出现过）。
     */
    private static boolean isDelete(CatalogItem item) {
        String event = item.getEvent();
        return event != null && "DELETE".equalsIgnoreCase(event.trim());
    }

    /**
     * 清理超时未收口的批次：若某批次只来一帧且 {@code SumNum} 恒大于实际条数，后续再无帧到达，
     * 就没人来收口，会永久占内存。
     */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Batch> e : pending.entrySet()) {
            Batch b = e.getValue();
            synchronized (b) {
                if (now - b.startedAt > WINDOW_MS && pending.remove(e.getKey()) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 当前正在聚合的批次数（测试/监控用）。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 丢弃某设备的全部未收口批次（设备注销时调用）。 */
    public void forget(String deviceId) {
        if (deviceId == null) {
            return;
        }
        pending.keySet().removeIf(k -> k.startsWith(deviceId + "#"));
    }
}
