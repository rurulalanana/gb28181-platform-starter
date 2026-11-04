package io.github.rurulalanana.starter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.starter.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 大华设备侧录像计划（{@code "Record"} 配置项）。
 *
 * <h3>为什么录像开关要改计划，而不是调个"开始录像"接口</h3>
 * 国标 {@code Record}/{@code StopRecord} 的语义是「让<b>设备</b>开始/停止录像」。
 * 大华 SDK 里没有对应的开关接口：
 * <ul>
 *   <li>{@code CLIENT_RecordStartEx} 录到的是<b>平台机器的磁盘</b>，不是设备 SD 卡
 *       （实测本机还返回 {@code 0x80000203}，该型号根本不支持）</li>
 *   <li>设备侧录像完全由<b>录像计划</b>决定，所以开关录像 = 改计划</li>
 * </ul>
 *
 * <h3>配置项结构（真机实测）</h3>
 * <pre>
 * {
 *   "Format":"dav", "HolidayEnable":false, "PreRecord":5,
 *   "Redundancy":false, "SaveAudio":true, "SnapShot":false, "Stream":0,
 *   "TimeSection":[
 *     ["6 00:00:00-23:59:59","0 00:00:00-23:59:59", … 共 6 段],   ← 组 0
 *     … 共 8 组
 *   ]
 * }
 * </pre>
 * 每段格式 {@code "<天数类型> <开始>-<结束>"}，<b>天数类型 0 表示该时段不录像</b>。
 *
 * <h3>写入必须守的三条规矩</h3>
 * <ol>
 *   <li><b>先读后写</b>：配置是整段覆盖，凭空构造会把没写的字段清零</li>
 *   <li><b>只写业务层</b>：{@code id}/{@code result}/{@code session} 是响应包外壳，
 *       写进去会被当成业务字段嵌进 table，且 merge 语义下删不掉
 *       （{@link DahuaExtAccess#writeConfig} 已做归一，这里靠它兜底）</li>
 *   <li><b>写完回读</b>：{@code CLIENT_SetNewDevConfig} 返回 true 不代表生效</li>
 * </ol>
 */
@Slf4j
@Service
public class DahuaRecordPlanService {

    /** 配置项命令名。 */
    private static final String CFG_RECORD = "Record";

    /** 「每天」类型的天数标记 —— 非 0 即启用，1 是设备默认的"普通/每天"。 */
    private static final String ALWAYS = "1";

    /** 关闭录像时使用的天数标记。 */
    private static final String OFF = "0";

    /** 一个时段的时间范围：设备只接受 23:59:59 作为终点（写 24:00:00 会被拒）。 */
    private static final String FULL_DAY = "00:00:00-23:59:59";

    private final DahuaCapabilityService dahuaCapability;
    private final ObjectMapper mapper;

    public DahuaRecordPlanService(DahuaCapabilityService dahuaCapability, ObjectMapper mapper) {
        this.dahuaCapability = dahuaCapability;
        this.mapper = mapper;
    }

    /**
     * 一个时段的解析结果。
     *
     * @param raw     原始字符串（{@code "6 00:00:00-23:59:59"}）
     * @param dayType 天数类型，0 = 不录像
     * @param start   起始时间
     * @param end     结束时间
     * @param enabled 是否启用录像
     */
    public record Slot(String raw, int dayType, String start, String end, boolean enabled) {
    }

    /**
     * 读取某通道的录像计划。
     *
     * @return 该通道的时段列表；配置项读不到时抛业务异常
     */
    public List<Slot> read(String deviceId, String channelCode) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);
        JsonNode root = readRoot(session, sdkChannel);
        return slotsOf(root, sdkChannel);
    }

    /**
     * 开 / 关设备侧录像。
     *
     * <p>实现方式是改录像计划：开启时把该通道第一个时段设成「每天 00:00:00-23:59:59」，
     * 关闭时把该通道所有时段的天数标记置 0。其余字段原样保留。
     *
     * <p><b>写完会回读校验</b>：设备对不接受的写入照样返回 true，
     * 不回读就会显示「已开启」而设备根本没录。
     *
     * @param start {@code true} 开始录像，{@code false} 停止
     * @return 回读得到的实际时段列表
     */
    public List<Slot> setRecording(String deviceId, String channelCode, boolean start) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);

        JsonNode root = readRoot(session, sdkChannel);
        ArrayNode timeSection = timeSectionOf(root);
        if (timeSection == null) {
            throw BizException.badRequest(
                    "设备返回的录像计划里没有 TimeSection 字段，拒绝写入（避免写坏配置）");
        }
        if (sdkChannel >= timeSection.size()) {
            throw BizException.badRequest("录像计划只有 " + timeSection.size()
                    + " 组，没有第 " + sdkChannel + " 通道，无法设置");
        }

        ArrayNode group = (ArrayNode) timeSection.get(sdkChannel);
        int slots = group.size();
        for (int i = 0; i < slots; i++) {
            boolean firstSlot = i == 0;
            String value = start
                    ? (firstSlot ? ALWAYS + " " + FULL_DAY : OFF + " " + FULL_DAY)
                    : OFF + " " + FULL_DAY;
            group.set(i, group.textNode(value));
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BizException.badRequest("录像计划序列化失败：" + e.getOriginalMessage());
        }

        try {
            dahuaCapability.ext().writeConfig(session, CFG_RECORD, sdkChannel, payload);
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("设置录像计划", e);
        }

        // 回读：写入返回 true ≠ 生效
        JsonNode after = readRoot(session, sdkChannel);
        List<Slot> actual = slotsOf(after, sdkChannel);
        boolean anyEnabled = actual.stream().anyMatch(Slot::enabled);
        if (start != anyEnabled) {
            /*
             * 报错里带上设备实际回读到的值，而不是只说「没生效」。
             * 光看「没生效」无法判断是设备拒绝、还是写到了别的通道、
             * 还是字段格式被归一化 —— 有原始值才能定位。
             */
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < actual.size(); i++) {
                if (i > 0) {
                    detail.append(" / ");
                }
                detail.append(actual.get(i).raw());
            }
            throw BizException.badRequest("录像计划已下发，但设备回读仍是「"
                    + (anyEnabled ? "录像中" : "未录像") + "」。设备实际时段：" + detail
                    + "。该型号可能不接受通过 SDK 修改录像计划，请在设备 Web 界面设置");
        }
        log.info("大华录像计划已{}: device={} channel={} 时段={}",
                start ? "开启" : "关闭", deviceId, channelCode, actual);
        return actual;
    }

    // ==================== 内部 ====================

    /** 读配置并解析成树，只保留业务层。 */
    private JsonNode readRoot(io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
                              int sdkChannel) {
        String json;
        try {
            json = dahuaCapability.ext().readConfig(session, CFG_RECORD, sdkChannel);
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("读取录像计划", e);
        }
        if (json == null || json.isBlank()) {
            throw BizException.badRequest("设备未返回录像计划（该型号可能不支持此配置项）");
        }
        String business = DahuaExtAccess.businessLayer(json);
        try {
            // 解析成可变树：写入前要改字段，不可变节点改不了
            return mapper.readTree(business);
        } catch (Exception e) {
            throw BizException.badRequest("录像计划不是合法 JSON，拒绝写入：" + e.getMessage());
        }
    }

    /** 取 TimeSection 数组；没有该字段返回 {@code null}。 */
    private static ArrayNode timeSectionOf(JsonNode root) {
        JsonNode ts = root == null ? null : root.get("TimeSection");
        return ts instanceof ArrayNode array ? array : null;
    }

    /** 解析某通道的时段列表。 */
    private static List<Slot> slotsOf(JsonNode root, int sdkChannel) {
        List<Slot> out = new ArrayList<>();
        ArrayNode ts = timeSectionOf(root);
        if (ts == null || sdkChannel >= ts.size()) {
            return out;
        }
        JsonNode group = ts.get(sdkChannel);
        if (!group.isArray()) {
            return out;
        }
        for (JsonNode item : group) {
            out.add(parseSlot(item.asText("")));
        }
        return out;
    }

    /**
     * 解析一段 {@code "<天数类型> <开始>-<结束>"}。
     *
     * <p>格式不对时返回 {@code dayType=0}（视为不录像）而不是抛异常 ——
     * 一个坏时段不该让整张计划打不开。
     *
     * <p>包级可见是为了单测：这段解析直接决定界面显示的「录像中/不录像」，
     * 解析错了会让用户看到与实际相反的状态。
     */
    static Slot parseSlot(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Slot(raw == null ? "" : raw, 0, null, null, false);
        }
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space <= 0) {
            return new Slot(raw, 0, null, null, false);
        }
        int dayType;
        try {
            dayType = Integer.parseInt(s.substring(0, space));
        } catch (NumberFormatException e) {
            dayType = 0;
        }
        // 设备返回的字段间可能有多个空格，必须 trim，否则时间串会带上前导空白
        String range = s.substring(space + 1).trim();
        int dash = range.indexOf('-');
        String start = dash > 0 ? range.substring(0, dash).trim() : range;
        String end = dash > 0 ? range.substring(dash + 1).trim() : null;
        return new Slot(raw, dayType, start, end, dayType != 0);
    }
}
