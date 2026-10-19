package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录分页聚合的回归测试。
 *
 * <p>现场问题：下级平台（LiveGBS v250103）按 GB/T 28181 附录 A.2.4 的分页行为上报，
 * 有 3 个通道时会连发 3 个 Catalog Response，每帧 {@code SumNum=3} 但只含 1 个
 * {@code <Item>}。原实现逐帧独立落库，导致「本次目录项」恒为 1，
 * 用户看到「推了 4 个通道上来这边只显示 2 个」。
 *
 * <p>这些断言把「必须按 SumNum 聚合」钉死，避免以后再退回逐帧处理。
 */
class CatalogPagerTest {

    /** 造一个目录项。 */
    private static CatalogItem item(String id) {
        return CatalogItem.builder().deviceId(id).name("ch-" + id).build();
    }

    @Test
    @DisplayName("分页上报：三帧各一条，SumNum=3 时前两帧不落库、第三帧一次给出全部")
    void shouldMergePagedCatalogBySumNum() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000002000000006";

        // 第 1 帧：只有设备自身那条
        assertNull(pager.accept(dev, 1, 3, List.of(item(dev))),
                "只收到 1/3 时不应收口，否则会把「只有自身」误判为直连摄像机");

        // 第 2 帧：第一个真实通道
        assertNull(pager.accept(dev, 1, 3, List.of(item("34020000001310000002"))),
                "收到 2/3 仍不应收口");

        // 第 3 帧：第二个真实通道 → 收齐
        CatalogPager.Result result = pager.accept(dev, 1, 3, List.of(item("34020000001320000001")));
        assertNotNull(result, "收齐 3/3 必须收口");
        assertTrue(result.complete(), "收齐时 complete 必须为 true，否则不会清理已下线通道");
        assertEquals(3, result.items().size(), "三帧的条目必须合并成 3 条");
        assertEquals(0, pager.pendingCount(), "收口后批次必须清除，避免 SN 复用时串数据");
    }

    @Test
    @DisplayName("单帧全量：SumNum 与条目数相等时立即收口")
    void shouldFinishImmediatelyWhenSingleFrame() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000001310000001";

        CatalogPager.Result result = pager.accept(dev, 7, 1, List.of(item(dev)));
        assertNotNull(result, "一帧就满足 SumNum 时应立即收口");
        assertTrue(result.complete());
        assertEquals(1, result.items().size());
    }

    @Test
    @DisplayName("无 SumNum：无法判断是否收齐，必须立即收口且标记为不完整")
    void shouldFinishButNotCompleteWhenSumNumMissing() {
        CatalogPager pager = new CatalogPager();
        String dev = "10000";

        CatalogPager.Result result = pager.accept(dev, 1, null, List.of(item(dev)));
        assertNotNull(result, "没有 SumNum 时不能一直挂着等，必须立即处理");
        assertTrue(!result.complete(),
                "没有 SumNum 不能当作全量快照，否则会误删本次未上报的通道");
    }

    @Test
    @DisplayName("重复条目按设备编码去重，不会把 SumNum 撑大")
    void shouldDeduplicateByDeviceId() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000002000000002";

        assertNull(pager.accept(dev, 3, 2, List.of(item("50015400001320000050"))));
        // 同一通道再来一次（设备重传），不应算作第 2 条
        assertNull(pager.accept(dev, 3, 2, List.of(item("50015400001320000050"))),
                "重复条目不能被当成新的进度");
        CatalogPager.Result result = pager.accept(dev, 3, 2, List.of(item("50015400531317000100")));
        assertNotNull(result);
        assertEquals(2, result.items().size());
    }

    @Test
    @DisplayName("不同 SN 的并发查询互不干扰")
    void shouldKeepDifferentSnSeparate() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000002000000006";

        // 两个并发查询（定时采集 + 手动刷新）各自只收到 1/2
        assertNull(pager.accept(dev, 100, 2, List.of(item("34020000001310000002"))));
        assertNull(pager.accept(dev, 200, 2, List.of(item("34020000001320000001"))));
        assertEquals(2, pager.pendingCount(), "两个 SN 应各自维护独立批次");

        // 分别收齐，互不混入对方的条目
        CatalogPager.Result a = pager.accept(dev, 100, 2, List.of(item("34020000001320000002")));
        assertNotNull(a);
        assertEquals(2, a.items().size());

        CatalogPager.Result b = pager.accept(dev, 200, 2, List.of(item("34020000001320000003")));
        assertNotNull(b);
        assertEquals(2, b.items().size());
        assertEquals(0, pager.pendingCount());
    }

    @Test
    @DisplayName("forget 丢弃指定设备的未收口批次")
    void shouldForgetDeviceBatches() {
        CatalogPager pager = new CatalogPager();
        pager.accept("dev-A", 1, 5, List.of(item("ch-1")));
        pager.accept("dev-B", 1, 5, List.of(item("ch-2")));
        assertEquals(2, pager.pendingCount());

        pager.forget("dev-A");
        assertEquals(1, pager.pendingCount(), "只清 dev-A 的批次");
    }

    @Test
    @DisplayName("结果携带下级自报条数与帧数，便于定位通道数差异")
    void shouldReportSumNumAndFrames() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000002000000006";

        assertNull(pager.accept(dev, 1, 2, List.of(item(dev))));
        CatalogPager.Result r = pager.accept(dev, 1, 2, List.of(item("34020000001320000001")));

        assertNotNull(r);
        assertEquals(2, r.sumNum(), "结果应带上 SumNum，否则日志无法区分「下级只报了 N 个」");
        assertEquals(2, r.frames(), "结果应带上实际收到的帧数，用于区分分页风格");
        assertTrue(r.complete());
    }

    @Test
    @DisplayName("单帧上报时帧数为 1，SumNum 与条目数一致")
    void shouldHandleSingleFrameWithFramesOne() {
        CatalogPager pager = new CatalogPager();
        String dev = "34020000002000000006";

        CatalogPager.Result r = pager.accept(dev, 1, 2,
                List.of(item(dev), item("34020000001320000001")));

        assertNotNull(r);
        assertEquals(1, r.frames(), "一次给全时帧数必须是 1");
        assertEquals(2, r.sumNum());
        assertEquals(2, r.items().size());
        assertTrue(r.complete());
    }
}
