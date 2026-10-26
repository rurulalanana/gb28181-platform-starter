package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbPreset;
import io.github.rurulalanana.starter.vo.PresetListVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预置位列表服务：承接设备的 {@code PresetQuery} 应答，供界面回显。
 *
 * <h3>为什么需要它（原实现为什么「查不到」）</h3>
 * 平台早就能<b>下发</b>预置位查询（{@code ControlService#queryPresets}），
 * 但链路上有两处断点，合起来就是「点了查询什么都没发生」：
 * <ol>
 *   <li>设备回的 MANSCDP {@code Response(CmdType=PresetQuery)} 在
 *       {@code SipEventBridge#onManscdpResponse} 里<b>没有分支</b>，
 *       落到 {@code default} 被静默丢弃 —— 数据到进程里就没了；</li>
 *   <li>{@code GET} 侧也没有任何接口暴露预置位列表
 *       （{@code DeviceService#presetsOf} 写了却无人调用），
 *       前端更没有入口发起查询。</li>
 * </ol>
 * 本服务补第 1 步的落地与第 2 步的数据源。
 *
 * <h3>为什么只放内存、不落库</h3>
 * 设备预置位是<b>设备自身的配置</b>，平台只做展示与转发：
 * <ul>
 *   <li>它随时可能在设备侧被改（现场调试、设备恢复出厂），
 *       落库得到的是一份会过期的副本，反而误导用户；</li>
 *   <li>查询本身很便宜（一条 SIP {@code MESSAGE}，设备通常几百毫秒内应答），
 *       打开面板时重查一次比维护同步逻辑更可靠；</li>
 *   <li>名称维度的持久化需求已经由 {@code gb_preset}（平台自定义名称）覆盖，
 *       两张表存同一个东西只会产生「以谁为准」的歧义。</li>
 * </ul>
 * 因此这里用带时间戳的内存缓存，进程重启后为空 —— 界面会重新查询，用户无感。
 */
@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    /** 通道 → 设备上报的预置位快照。键是通道编码。 */
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    private final DeviceService deviceService;

    public PresetService(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 处理设备的 {@code PresetQuery} 应答。
     *
     * <p>解析的是标准 A.2.4.8 的应答体：
     * <pre>
     * &lt;Response&gt;
     *   &lt;CmdType&gt;PresetQuery&lt;/CmdType&gt;
     *   &lt;DeviceID&gt;34020000001320000001&lt;/DeviceID&gt;
     *   &lt;PresetList Num="3"&gt;
     *     &lt;Item&gt;&lt;PresetID&gt;1&lt;/PresetID&gt;&lt;PresetName&gt;大门&lt;/PresetName&gt;&lt;Status&gt;OK&lt;/Status&gt;&lt;/Item&gt;
     *   &lt;/PresetList&gt;
     * &lt;/Response&gt;
     * </pre>
     *
     * <h3>⚠️ 必须按 SN 累积，不能逐帧覆盖</h3>
     * 设备会把预置位<b>拆成多帧</b>上报，且<b>没有 {@code SumNum} 之类的总数字段</b>
     * （{@code PresetList@Num} 只是本帧条数）。实测一台球机有 5 个预置位时发了两帧：
     * <pre>
     * 第一帧: &lt;PresetList Num="4"&gt; 1 2 3 4 &lt;/PresetList&gt;
     * 第二帧: &lt;PresetList Num="1"&gt; 5 &lt;/PresetList&gt;
     * </pre>
     * 早先实现是「收到一帧就用它替换缓存」，于是第二帧的 {@code [5]} 把
     * {@code [1,2,3,4]} 覆盖掉 —— 界面只显示 1 个预置位，
     * 看上去像「设置新预置位把其他的都清了」，实际上设备上 5 个都在。
     *
     * <p>用应答里的 {@code <SN>} 区分批次：同一次查询的所有帧 SN 相同（SN 本来就用于
     * 匹配请求与应答），SN 变化就是新的一次查询，此时才清空重来。
     *
     * <p><b>{@code PresetList} 缺失也要记录</b>：部分设备用「空应答」表示"我一个预置位都没有"，
     * 与"设备没回"是两件事 —— 前者要让用户看到「设备上没有预置位」，
     * 后者要继续等。所以这里无条件下写一条快照（哪怕 items 为空）。
     *
     * <p><b>编号解析失败的条目直接跳过</b>：厂商私有扩展里偶尔会出现
     * 非数字的 {@code PresetID}（如 {@code "A1"}），一个坏条目不该让整表作废。
     *
     * @param channelId  通道编码（设备的 {@code <DeviceID>}）
     * @param sn         应答的 {@code <SN>}，同一批次的帧相同；{@code null} 表示无法区分批次
     * @param containers 全部 {@code <PresetList>} 容器（一帧里也可能有多个）
     */
    public void onPresetList(String channelId, Integer sn, List<Element> containers) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        List<PresetListVo.Item> parsed = new ArrayList<>();
        for (Element container : containers) {
            for (Element item : childrenNamed(container, "Item")) {
                Integer id = intOf(ManscdpText.first(item, "PresetID"));
                if (id == null) {
                    log.debug("预置位条目编号无法解析，已跳过: channel={} text={}",
                            channelId, ManscdpText.first(item, "PresetID"));
                    continue;
                }
                PresetListVo.Item vo = new PresetListVo.Item();
                vo.setPresetId(id);
                vo.setDeviceName(blankToNull(ManscdpText.first(item, "PresetName")));
                vo.setStatus(blankToNull(ManscdpText.first(item, "Status")));
                parsed.add(vo);
            }
        }
        cache.compute(channelId, (key, old) -> {
            boolean sameBatch = old != null && sn != null && sn.equals(old.sn());
            if (!sameBatch) {
                // 新的一次查询（或无法判断批次）：以本帧为起点，清掉上一轮残留
                return Snapshot.of(sn, parsed);
            }
            // 同一批次：按编号合并，后到的同编号条目覆盖先到的
            Map<Integer, PresetListVo.Item> merged = new LinkedHashMap<>();
            for (PresetListVo.Item i : old.items()) {
                merged.put(i.getPresetId(), i);
            }
            for (PresetListVo.Item i : parsed) {
                merged.put(i.getPresetId(), i);
            }
            return Snapshot.of(sn, new ArrayList<>(merged.values()));
        });
        Snapshot now = cache.get(channelId);
        log.info("预置位已同步: channel={} 本帧={} 累计={} sn={}",
                channelId, parsed.size(), now == null ? 0 : now.items().size(), sn);
    }

    /**
     * 取某通道的预置位列表（设备快照 + 平台自定义名称合并）。
     *
     * @return 恒返回非 {@code null} 的对象；{@code queriedAt} 为 {@code null}
     *         表示还没收到过设备应答
     */
    public PresetListVo list(String channelId) {
        PresetListVo vo = new PresetListVo();
        vo.setChannelId(channelId);
        Snapshot snap = channelId == null ? null : cache.get(channelId);
        List<PresetListVo.Item> items = snap == null ? new ArrayList<>() : copy(snap.items());
        if (snap != null) {
            // 用项目统一的时间格式：LocalDateTime#toString 会带上纳秒
            // （2026-10-26 16:23:30.991820635），既难看又会让前端字符串比较失效
            vo.setQueriedAt(TimeFormats.toDisplay(snap.at()));
        }
        // 合并平台自定义名称：编号以设备为准，名称优先用自定义的
        Map<Integer, String> custom = customNames(channelId);
        for (PresetListVo.Item item : items) {
            String name = custom.get(item.getPresetId());
            if (name != null && !name.isBlank()) {
                item.setName(name);
                item.setCustomName(true);
            } else {
                item.setName(item.getDeviceName() != null
                        ? item.getDeviceName() : "预置位 " + item.getPresetId());
                item.setCustomName(false);
            }
        }
        vo.setItems(items);
        vo.setCount(items.size());
        return vo;
    }

    /** 设备已应答过的通道集合（排障用）。 */
    public int cachedChannelCount() {
        return cache.size();
    }

    private Map<Integer, String> customNames(String channelId) {
        Map<Integer, String> map = new ConcurrentHashMap<>();
        if (channelId == null || channelId.isBlank()) {
            return map;
        }
        try {
            List<GbPreset> presets = deviceService.presetsOf(channelId);
            for (GbPreset p : presets) {
                if (p.getPresetId() != null && p.getName() != null) {
                    map.put(p.getPresetId(), p.getName());
                }
            }
        } catch (Exception e) {
            // 名称只是锦上添花，查库失败不该让整张表打不开
            log.debug("读取预置位自定义名称失败: channel={} {}", channelId, e.getMessage());
        }
        return map;
    }

    /**
     * 拷贝一份再交给调用方。
     *
     * <p>缓存里的对象会被后续应答整体替换，但其中的 {@code Item}
     * 在合并名称时是原地修改的 —— 不拷贝就会把「本次合并结果」写回缓存，
     * 用户改了自定义名称后旧值仍留在 {@code deviceName} 之外的字段上，
     * 表现为「名称改不回来」。
     */
    private static List<PresetListVo.Item> copy(List<PresetListVo.Item> src) {
        List<PresetListVo.Item> out = new ArrayList<>(src.size());
        for (PresetListVo.Item s : src) {
            PresetListVo.Item n = new PresetListVo.Item();
            n.setPresetId(s.getPresetId());
            n.setDeviceName(s.getDeviceName());
            n.setStatus(s.getStatus());
            out.add(n);
        }
        return out;
    }

    private static Integer intOf(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 一次设备应答的快照。
     *
     * @param sn 应答的 {@code <SN>}，用于区分「同一次查询的多帧」与「新的一次查询」
     */
    private record Snapshot(Integer sn, LocalDateTime at, List<PresetListVo.Item> items) {

        /** 构造时按编号升序排好，界面直接按顺序显示。 */
        static Snapshot of(Integer sn, List<PresetListVo.Item> items) {
            List<PresetListVo.Item> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparing(PresetListVo.Item::getPresetId));
            return new Snapshot(sn, LocalDateTime.now(), sorted);
        }
    }

    /**
     * 取元素的直接子元素。
     *
     * <p>不用 {@code getElementsByTagName}：那是深度遍历，容器里若嵌了同名节点会多取。
     */
    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        var nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            var n = nl.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /**
     * 只在<b>直接子元素</b>里取文本的小工具。
     *
     * <p>不用 {@code getElementsByTagName}：那是深度遍历，
     * 厂商私有扩展里若嵌了同名节点（如 {@code Ext/PresetID}）会命中错误的值。
     */
    private static final class ManscdpText {
        private ManscdpText() {
        }

        static String first(Element parent, String tag) {
            if (parent == null) {
                return null;
            }
            var nl = parent.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                var n = nl.item(i);
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && tag.equals(n.getNodeName())) {
                    return io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage
                            .textOf((Element) n);
                }
            }
            return null;
        }
    }
}
