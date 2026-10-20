package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅推送（SIP {@code NOTIFY}）里目录报文的解析回归测试。
 *
 * <p>现场问题：用户在下级平台点「推送通道」，本级毫无反应；只有把级联关掉重新注册
 * （触发全量 Catalog 查询应答）才能看到新通道。
 *
 * <p>根因：国标订阅推送有两条信令路径，协议栈把它们分派到不同回调：
 * <ul>
 *   <li>{@code MESSAGE} + MANSCDP 体 → {@code onManscdpRequest}</li>
 *   <li>{@code NOTIFY} + MANSCDP 体 → {@code onNotify}</li>
 * </ul>
 * 而 {@code onNotify} 此前是空实现（误以为 NOTIFY 体也会走 onManscdpRequest），
 * 于是所有走 NOTIFY 的推送被静默丢弃。
 *
 * <p>本测试锁定「NOTIFY 体里的 Catalog 必须能被 {@link ManscdpXml#parse} 正常解析，
 * 且 {@code CmdType} / {@code DeviceID} / {@code SumNum} / {@code Item} 都能取到」，
 * 这是 onNotify 分支得以工作的前提。
 */
class NotifyCatalogParsingTest {

    /** 构造一个典型的订阅推送目录报文（根元素为 Notify）。 */
    private static String notifyCatalogXml(int sumNum, String... channelIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\n");
        sb.append("<Notify>\n");
        sb.append("<CmdType>Catalog</CmdType>\n");
        sb.append("<SN>17</SN>\n");
        sb.append("<DeviceID>34020000002000000006</DeviceID>\n");
        sb.append("<SumNum>").append(sumNum).append("</SumNum>\n");
        sb.append("<DeviceList Num=\"").append(channelIds.length).append("\">\n");
        for (String id : channelIds) {
            sb.append("  <Item>\n");
            sb.append("    <DeviceID>").append(id).append("</DeviceID>\n");
            sb.append("    <Name>通道-").append(id).append("</Name>\n");
            sb.append("    <ParentID>34020000002000000006</ParentID>\n");
            sb.append("    <Status>ON</Status>\n");
            sb.append("  </Item>\n");
        }
        sb.append("</DeviceList>\n");
        sb.append("</Notify>\n");
        return sb.toString();
    }

    @Test
    @DisplayName("NOTIFY 根元素的 Catalog 推送能被解析：CmdType/DeviceID/SumNum/Item 齐全")
    void shouldParseCatalogFromNotifyRoot() {
        String xml = notifyCatalogXml(3,
                "34020000001310000002",
                "34020000001320000001",
                "34020000001320000003");

        ManscdpMessage msg = ManscdpXml.parse(xml);

        assertNotNull(msg, "NOTIFY 报文必须能解析成 ManscdpMessage");
        assertEquals("Catalog", msg.getCmdType(), "CmdType 必须是 Catalog");
        assertEquals("34020000002000000006", msg.getDeviceId(),
                "DeviceID 必须取到，否则无法确定目录归属设备");
        assertEquals(Integer.valueOf(3), msg.intOf("SumNum"),
                "SumNum 必须取到，分页聚合依赖它判定是否收齐");
        assertEquals(17, msg.getSn(), "SN 必须取到，用于区分并发查询批次");

        List<org.w3c.dom.Element> items = msg.items("DeviceList");
        assertEquals(3, items.size(), "三个 Item 必须全部解析出来");
    }

    @Test
    @DisplayName("NOTIFY 推送的条目经聚合器后能被 SipEventBridge 的解析逻辑还原成 CatalogItem")
    void shouldConvertNotifyItemsToCatalogItems() {
        String xml = notifyCatalogXml(2,
                "34020000001310000002",
                "34020000001320000001");

        ManscdpMessage msg = ManscdpXml.parse(xml);
        List<CatalogItem> items = SipEventBridge.parseCatalog(msg);

        assertEquals(2, items.size(), "两条通道必须都转成 CatalogItem");
        assertTrue(items.stream().anyMatch(i -> "34020000001310000002".equals(i.getDeviceId())),
                "必须包含南坑水库那条");
        assertTrue(items.stream().anyMatch(i -> "34020000001320000001".equals(i.getDeviceId())),
                "必须包含灯花水库库区球机那条");
        assertEquals("34020000002000000006", items.get(0).getParentId(),
                "ParentID 必须取到，否则通道会挂错设备");
    }

    @Test
    @DisplayName("增量推送不应被当作全量快照：聚合器在 SumNum 未收齐时不收口")
    void shouldNotTreatIncrementalPushAsFullSnapshot() {
        String xml = notifyCatalogXml(5, "34020000001310000002");
        ManscdpMessage msg = ManscdpXml.parse(xml);

        CatalogPager pager = new CatalogPager();
        CatalogPager.Result result = pager.accept(
                msg.getDeviceId(), msg.getSn(), msg.intOf("SumNum"),
                SipEventBridge.parseCatalog(msg));

        // 只收到 1/5，不能收口；否则会被当成「只有 1 个通道的全量目录」去删其它通道
        org.junit.jupiter.api.Assertions.assertNull(result,
                "增量推送只到 1/5 时不得收口，否则会误删未上报的通道");
    }

    @Test
    @DisplayName("真实报文里的 <Event>DELETE</Event> 必须被解析出来并路由到删除列表")
    void shouldParseEventDeleteFromRealPayload() {
        // 模拟下级平台删除通道时推送的报文（抓包实测格式：根元素为 <Response>）
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n"
                + "<Response>\n"
                + "  <CmdType>Catalog</CmdType>\n"
                + "  <SN>1621896</SN>\n"
                + "  <DeviceID>34020000002000000006</DeviceID>\n"
                + "  <SumNum>1</SumNum>\n"
                + "  <DeviceList Num=\"1\">\n"
                + "    <Item>\n"
                + "      <DeviceID>34020000001320000001</DeviceID>\n"
                + "      <Name>灯花水库库区球机</Name>\n"
                + "      <Event>DELETE</Event>\n"
                + "    </Item>\n"
                + "  </DeviceList>\n"
                + "</Response>\n";

        ManscdpMessage msg = ManscdpXml.parse(xml);
        List<CatalogItem> items = SipEventBridge.parseCatalog(msg);

        assertEquals(1, items.size());
        assertEquals("DELETE", items.get(0).getEvent(),
                "<Event> 必须被解析出来，否则下级删通道时本级会把通道加回来");

        CatalogPager pager = new CatalogPager();
        CatalogPager.Result result = pager.accept(
                msg.getDeviceId(), msg.getSn(), msg.intOf("SumNum"), items);

        assertNotNull(result);
        assertEquals(0, result.items().size(), "删除项不能进 upsert 列表");
        assertEquals(1, result.removed().size());
        assertEquals("34020000001320000001", result.removed().get(0));
    }
}
