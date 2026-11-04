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
 * <p>只放内存、不落库：预置位是设备自身的配置，随时可能在设备侧被改，落库只是一份会过期的副本；
 * 查询本身很便宜（一条 SIP MESSAGE，几百毫秒应答），打开面板时重查比维护同步逻辑可靠；
 * 名称维度的持久化由 {@code gb_preset}（平台自定义名称）覆盖。
 */
@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    /** 通道 → 设备上报的预置位快照。键是通道编码。 */
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    private final DeviceService deviceService;

    /** SDK 直连设备的预置位走同步读，与国标「下发+等应答」是两条路。 */
    private final DahuaCapabilityService dahuaCapability;

    public PresetService(DeviceService deviceService, DahuaCapabilityService dahuaCapability) {
        this.deviceService = deviceService;
        this.dahuaCapability = dahuaCapability;
    }

    /**
     * 处理设备的 {@code PresetQuery} 应答（标准 A.2.4.8）。
     *
     * <p>必须按 SN 累积，不能逐帧覆盖：设备会把预置位拆成多帧且没有总数字段
     * （{@code PresetList@Num} 只是本帧条数）—— 实测 5 个预置位发两帧 4 + 1，
     * 逐帧替换会让界面只剩最后 1 个。同一次查询的各帧 SN 相同，SN 变化才清空重来。
     *
     * <p>无 {@code PresetList} 也要写一条空快照（表示「设备上没有预置位」，与「没回」不同）；
     * 编号解析失败的条目直接跳过，一个坏条目不该让整表作废。
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
     * @return 恒非 {@code null}；{@code queriedAt} 为 {@code null} 表示还没收到过设备应答
     */
    public PresetListVo list(String channelId) {
        PresetListVo vo = new PresetListVo();
        vo.setChannelId(channelId);
        Snapshot snap = channelId == null ? null : cache.get(channelId);
        List<PresetListVo.Item> items = snap == null ? new ArrayList<>() : copy(snap.items());
        if (snap != null) {
            // 用项目统一的时间格式：LocalDateTime#toString 会带纳秒，既难看又会让前端字符串比较失效
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

    /**
     * 向 SDK 直连设备<b>实时</b>读取预置点（大华等）。
     *
     * <p>与国标路径的区别：国标是「下发查询 → 等设备应答 → 落内存快照」，
     * 设备可能几秒不答甚至永不答；SDK 是同步调用，直接拿回结果，
     * 因此不需要缓存，也就不存在「查到的是上一次的」。
     *
     * <h3>清单只来自设备</h3>
     * 早期平台自己存过坐标做兜底，那是因为误判了「设备不支持预置点」。
     * 实际上设备完全支持（{@code QueryDevState(0x57)} 能读到名称与坐标），
     * 平台那份副本只会带来「两边不同步」和「设备上没有却显示存在」的假数据，已移除。
     *
     * <p>平台唯一保留的预置点数据是<b>名称</b>（{@code gb_preset.name}）——
     * 设备不存这个，只有平台知道，用于给编号起一个本域内的别名。
     *
     * @param deviceId    设备编号（SDK 会话按它取）
     * @param channelCode 通道编码
     * @param channelId   通道编码（与 channelCode 相同；保留参数名以对齐国标口径）
     */
    public PresetListVo listBySdk(String deviceId, String channelCode, String channelId) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);
        Map<Integer, String> custom = customNames(channelId);

        List<PresetListVo.Item> items = new ArrayList<>();
        try {
            for (var p : dahuaCapability.ext().listPresets(session, sdkChannel)) {
                PresetListVo.Item item = new PresetListVo.Item();
                item.setPresetId(p.index());
                /*
                 * 名称优先级：平台自定义名 > 设备名 > 默认名。
                 * 设备名来自 QueryDevState(0x57)（GBK 解码），这是唯一能拿到
                 * 「用户在设备 Web 界面起的名字」的途径。
                 */
                String alias = custom.get(p.index());
                if (alias != null && !alias.isBlank()) {
                    item.setName(alias);
                    item.setCustomName(true);
                } else {
                    item.setName(p.name() != null && !p.name().isBlank()
                            ? p.name() : "预置点 " + p.index());
                    item.setCustomName(false);
                }
                item.setStatus(p.hasPosition()
                        ? ("设备坐标 " + p.pan() + "," + p.tilt() + "," + p.zoom())
                        : (p.staySeconds() > 0 ? ("停留 " + p.staySeconds() + "s") : null));
                items.add(item);
            }
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            /*
             * 读不到清单只有两种原因：型号不支持这三条接口，或会话失效。
             * 后者 requireSession 已经拦掉，所以这里必然是前者 —— 返回空表，
             * 并如实告知，而不是编一份数据出来。
             */
            log.info("读不到设备预置点清单: device={} channel={} {}",
                    deviceId, channelCode, e.getMessage());
        }

        items.sort(Comparator.comparing(PresetListVo.Item::getPresetId));

        PresetListVo vo = new PresetListVo();
        vo.setChannelId(channelId);
        vo.setItems(items);
        vo.setCount(items.size());
        // SDK 是同步读，读到即此刻 —— 填当前时间，前端据此知道这是实时值而不是缓存
        vo.setQueriedAt(TimeFormats.toDisplay(LocalDateTime.now()));
        return vo;
    }

    /** 设备已应答过的通道集合（排障用）。 */
    public int cachedChannelCount() {
        return cache.size();
    }

    /**
     * 该 SDK 错误是否表示「型号不支持这个配置项」。
     *
     * <p><b>实测关键事实</b>：{@code CLIENT_GetNewDevConfig} 失败时
     * <b>不会</b>填错误码 —— 实测返回 {@code false} 且 {@code nError=0}。
     * 所以不能靠错误码判断，只能按「读不到配置项」这个事实本身来判。
     *
     * <p>这是可以接受的近似：读配置失败只有两种原因 ——
     * 型号不支持该配置项，或会话已失效。后者在调用前
     * {@code requireSession} 已经拦掉（会抛「设备未登录」），
     * 走到这里的必然是前者。
     */
    private static boolean isConfigUnsupported(
            io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        // 已知的「配置项不存在 / 不支持」错误码，或错误码为 0（SDK 未填）
        return e.sdkError() == 0
                || e.sdkError() == 0x80000018
                || e.sdkError() == 0x8000004F
                || msg.contains("读取配置失败");
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
     * 拷贝一份再交给调用方：缓存里的 {@code Item} 在合并名称时是原地修改的，
     * 不拷贝会把本次结果写回缓存，用户改完自定义名称后会「改不回来」。
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
     * @param sn 应答的 {@code <SN>}，用于区分同一次查询的多帧与新的一次查询
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
     * 取元素的直接子元素。不用 {@code getElementsByTagName}：那是深度遍历，容器里嵌了同名节点会多取。
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
