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
 * 分页目录聚合器。
 *
 * <p><b>为什么需要它</b>：GB/T 28181 附录 A.2.4 的 Catalog 应答支持分页 ——
 * 一个 {@code Response} 里的 {@code <SumNum>} 是本次查询的<b>总条数</b>，
 * 而 {@code <DeviceList Num="...">} 只携带其中一页。下级平台（实测 LiveGBS v250103）
 * 就是按这种标准行为上报的：有 3 个通道时它会连发 3 个独立的 Catalog Response，
 * 每帧 {@code SumNum=3} 但只含 1 个 {@code <Item>}。
 *
 * <p><b>不聚合会怎样</b>：每帧都被当成一次完整的全量目录去落库，
 * 于是「本次目录项」恒为 1，而设备实际有 3 个通道时库里只会留下被最后一帧覆盖的结果。
 * 用户看到的就是「下级平台推了 4 个通道上来，这边只显示 2 个」。
 *
 * <p><b>聚合策略</b>：按「设备编码 + 会话序号（SN）」分组累积，满足任一条件即收口：
 * <ul>
 *   <li>已累积条数 &ge; {@code SumNum}（正常收齐）</li>
 *   <li>距首帧超过 {@link #WINDOW_MS}（设备少报 / 丢包，不能无限等）</li>
 * </ul>
 * 收口后一次性交给 {@code DeviceService.saveCatalog}，由它按实际行数回填通道数。
 *
 * <p><b>为什么用 SN 分组</b>：同一台设备可能被并发查询（定时采集 + 手动刷新目录），
 * 两批应答交错到达时按 SN 分开累积，不会把两批的条目混成一份。
 * SN 在标准里是 1~9999 循环使用，因此分组会在收口时立即清除，避免长期占用内存。
 */
@Slf4j
@Component
public class CatalogPager {

    /**
     * 聚合窗口（毫秒）。
     *
     * <p>取 8 秒：实测下级平台连续上报 3 帧耗时不到 1 秒，
     * 留足余量覆盖跨网抖动，又不至于让用户点「刷新目录」后干等。
     */
    static final long WINDOW_MS = 8_000L;

    /**
     * 收口结果。
     *
     * @param items    累积到的全部目录项
     * @param complete 是否已收齐（已收条数 &ge; {@code SumNum}）。
     *                 仅当收齐时才能当作「全量快照」去清理已下线的旧通道。
     * @param sumNum   设备自报的总条数（{@code SumNum}），0 表示设备未提供。
     *                 带上它才能从日志区分「下级只共享了 2 个」和「下级共享 4 个但我们只收到 2 个」。
     * @param frames   本次批次实际收到的应答帧数（诊断分页上报风格）。
     */
    public record Result(List<CatalogItem> items, boolean complete, int sumNum, int frames) {
    }

    /** 正在聚合的批次，key = {@code deviceId#sn}。 */
    private final Map<String, Batch> pending = new ConcurrentHashMap<>();

    /** 一个聚合批次。 */
    private static final class Batch {
        /** 设备自报的总条数（{@code SumNum}），0 或 null 视为未知。 */
        private final int sumNum;
        /** 首帧到达时间。 */
        private final long startedAt = System.currentTimeMillis();
        /** 已累积的条目，按设备编码去重（标准要求设备编码全局唯一）。 */
        private final Map<String, CatalogItem> items = new LinkedHashMap<>();
        /** 收到的应答帧数（诊断用：可区分「单帧多条」与「多帧各一条」两种上报风格）。 */
        private int frames;

        Batch(int sumNum) {
            this.sumNum = sumNum;
        }
    }

    /**
     * 投递一帧目录应答。
     *
     * @param deviceId 应答来源设备（{@code Response} 里的 {@code DeviceID}）
     * @param sn       会话序号，用于区分同一设备的并发查询
     * @param sumNum   本次查询的总条数（{@code <SumNum>}），未知传 {@code null}
     * @param items    本帧携带的目录项
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
                    if (it != null && it.getDeviceId() != null && !it.getDeviceId().isBlank()) {
                        batch.items.putIfAbsent(it.getDeviceId(), it);
                    }
                }
            }
            // 没有 SumNum 的设备（大量国标 IPC / NVR 都不带这个字段）无从判断是否还有后续帧。
            // 若按「等超时」处理，这类设备的目录同步会被无谓拖慢 8 秒，
            // 而它们实际上都是单帧全量上报，直接收口即可。
            if (batch.sumNum <= 0) {
                pending.remove(key);
                return new Result(new ArrayList<>(batch.items.values()), false, 0, batch.frames);
            }
            boolean complete = batch.items.size() >= batch.sumNum;
            boolean expired = System.currentTimeMillis() - batch.startedAt > WINDOW_MS;
            if (!complete && !expired) {
                log.debug("目录分页累积中: device={} sn={} 已收={}/{}", deviceId, sn,
                        batch.items.size(), batch.sumNum);
                return null;
            }
            pending.remove(key);
            if (!complete) {
                // 少报通常意味着设备侧过滤（权限/离线通道不报），不是错误；
                // 但需要在日志里留痕，便于排查「通道数与设备侧不一致」
                log.info("目录分页未收齐即收口: device={} sn={} 已收={}/{} 帧数={}",
                        deviceId, sn, batch.items.size(), batch.sumNum, batch.frames);
            }
            if (log.isDebugEnabled()) {
                log.debug("目录分页收口: device={} sn={} 帧数={} 条目={} SumNum={} 收齐={} 设备编码={}",
                        deviceId, sn, batch.frames, batch.items.size(), batch.sumNum,
                        complete, batch.items.keySet());
            }
            return new Result(new ArrayList<>(batch.items.values()), complete,
                    batch.sumNum, batch.frames);
        }
    }

    /**
     * 清理超时未收口的批次。
     *
     * <p>由定时任务调用。正常情况下每帧都会在 {@link #accept} 里被收口，
     * 但若某批次只来了一帧且 {@code SumNum} 恒大于实际条数（设备少报），
     * 后续再无帧到达，就不会再有人来收口 —— 这个批次会永久占着内存。
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
