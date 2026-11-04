package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbRecordPlan;
import io.github.rurulalanana.starter.entity.GbRecordPlanChannel;
import io.github.rurulalanana.starter.mapper.GbRecordPlanChannelMapper;
import io.github.rurulalanana.starter.mapper.GbRecordPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 录像计划服务：哪些通道、每周哪几天、每天什么时段录云端录像。
 * 与 LiveGBS 兼容（{@code plan} 存 JSON，7 个数组对应周一~周日）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordPlanService {

    private final GbRecordPlanMapper planMapper;
    private final GbRecordPlanChannelMapper planChannelMapper;
    private final DeviceService deviceService;

    public List<GbRecordPlan> listAll() {
        return planMapper.selectList(Wrappers.<GbRecordPlan>lambdaQuery()
                .orderByDesc(GbRecordPlan::getCreateTime));
    }

    public GbRecordPlan find(String planId) {
        if (planId == null || planId.isBlank()) {
            return null;
        }
        return planMapper.selectOne(Wrappers.<GbRecordPlan>lambdaQuery()
                .eq(GbRecordPlan::getPlanId, planId).last("limit 1"));
    }

    /** 新增或更新计划（{@code planId} 为空则新建）。
     *
     * @param plan 时段规则 JSON 字符串
     * @return 计划 ID
     */
    @Transactional
    public String save(String planId, String name, String plan, Boolean enable) {
        GbRecordPlan p = planId == null || planId.isBlank() ? null : find(planId);
        if (p == null) {
            p = new GbRecordPlan();
            p.setPlanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            p.setCreateTime(LocalDateTime.now());
        }
        p.setName(name == null || name.isBlank() ? "未命名计划" : name.trim());
        p.setPlan(plan == null || plan.isBlank() ? "[[],[],[],[],[],[],[]]" : plan);
        p.setEnable(enable == null || enable);
        p.setUpdateTime(LocalDateTime.now());
        if (p.getId() == null) {
            planMapper.insert(p);
        } else {
            planMapper.updateById(p);
        }
        return p.getPlanId();
    }

    @Transactional
    public void setEnable(String planId, boolean enable) {
        GbRecordPlan p = find(planId);
        if (p == null) {
            return;
        }
        p.setEnable(enable);
        p.setUpdateTime(LocalDateTime.now());
        planMapper.updateById(p);
    }

    @Transactional
    public void remove(String planId) {
        planMapper.delete(Wrappers.<GbRecordPlan>lambdaQuery().eq(GbRecordPlan::getPlanId, planId));
        planChannelMapper.delete(Wrappers.<GbRecordPlanChannel>lambdaQuery()
                .eq(GbRecordPlanChannel::getPlanId, planId));
    }

    public Set<String> channelIds(String planId) {
        Set<String> ids = new HashSet<>();
        for (GbRecordPlanChannel c : planChannelMapper.selectList(
                Wrappers.<GbRecordPlanChannel>lambdaQuery().eq(GbRecordPlanChannel::getPlanId, planId))) {
            ids.add(c.getChannelId());
        }
        return ids;
    }

    /** 设置计划的关联通道（全量覆盖）。
     *
     * @param refs 通道引用，元素形如 {@code <设备编码>/<通道编码>}
     */
    @Transactional
    public void setChannels(String planId, List<String> refs) {
        if (planId == null || planId.isBlank()) {
            return;
        }
        planChannelMapper.delete(Wrappers.<GbRecordPlanChannel>lambdaQuery()
                .eq(GbRecordPlanChannel::getPlanId, planId));
        if (refs == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            int slash = ref.indexOf('/');
            String deviceId = slash > 0 ? ref.substring(0, slash) : ref;
            String channelId = slash > 0 ? ref.substring(slash + 1) : ref;
            String key = deviceId + "/" + channelId;
            if (!seen.add(key)) {
                continue;
            }
            GbRecordPlanChannel c = new GbRecordPlanChannel();
            c.setPlanId(planId);
            c.setDeviceId(deviceId);
            c.setChannelId(channelId);
            c.setCreateTime(LocalDateTime.now());
            planChannelMapper.insert(c);
        }
    }

    /**
     * 列出「可关联到录像计划的通道」：有录像的通道 + 全量通道。
     *
     * <p>与 LiveGBS 的「关联通道」弹窗一致：列出所有通道，让运维挑。
     *
     * @param keyword 关键字过滤（通道编码 / 名称）
     * @return 通道列表
     */
    public List<GbChannel> candidateChannels(String keyword) {
        List<GbChannel> all = deviceService.allChannels();
        if (keyword == null || keyword.isBlank()) {
            return all;
        }
        String needle = keyword.trim().toLowerCase();
        List<GbChannel> out = new ArrayList<>();
        for (GbChannel c : all) {
            String name = c.displayName() == null ? "" : c.displayName().toLowerCase();
            if (c.getChannelId().toLowerCase().contains(needle) || name.contains(needle)) {
                out.add(c);
            }
        }
        return out;
    }

    // ==================== 计划执行 ====================

    /**
     * 计划执行结果快照（供定时任务决策）。
     *
     * @param active   此刻<b>应在录制</b>的通道编码集合（多个计划的并集）
     * @param governed 被任一启用计划<b>覆盖</b>的通道编码集合（含此刻不该录的）
     */
    public record PlanScope(Set<String> active, Set<String> governed) {
    }

    /**
     * 计算此刻「计划判定应录」的通道集合：只表示「按时间表应该录」，不表示「要把通道开关改成什么」。
     * 调用方只把它写进内存（{@code ChannelRecordPolicy#replacePlanActive}），不写库、不改
     * {@code cloud_record}/{@code on_demand}；最终是否录 = 手动开关 ∪ 本信号。
     * 多个计划含同一通道取并集（白班 + 夜班期望两段都录），实现天然是 union。
     */
    public PlanScope scopeAt(LocalDateTime now) {
        Set<String> active = new HashSet<>();
        Set<String> governed = new HashSet<>();
        // 一次性取出「启用计划 → 通道」，避免逐计划查库
        List<GbRecordPlan> plans = planMapper.selectList(Wrappers.<GbRecordPlan>lambdaQuery()
                .eq(GbRecordPlan::getEnable, true));
        if (plans.isEmpty()) {
            return new PlanScope(active, governed);
        }
        java.util.Map<String, GbRecordPlan> byPlanId = new java.util.HashMap<>();
        for (GbRecordPlan p : plans) {
            byPlanId.put(p.getPlanId(), p);
        }
        for (GbRecordPlanChannel c : planChannelMapper.selectList(
                Wrappers.<GbRecordPlanChannel>lambdaQuery()
                        .in(GbRecordPlanChannel::getPlanId, byPlanId.keySet()))) {
            GbRecordPlan plan = byPlanId.get(c.getPlanId());
            if (plan == null || c.getChannelId() == null) {
                continue;
            }
            governed.add(c.getChannelId());
            // 并集：任一计划命中即 active，多个计划覆盖同一通道不会互相抵消
            if (inPlanWindow(plan.getPlan(), now)) {
                active.add(c.getChannelId());
            }
        }
        return new PlanScope(active, governed);
    }

    /**
     * 判断某时刻是否落在计划时段内。计划 JSON 与 LiveGBS 兼容：外层 7 个数组为周一~周日，
     * 键名里的数字即「第几天」（1~7），例如 {@code [[{"s1":"00:00","e1":"24:00"}], [], ...]}。
     * 跨零点段（如 23:00~02:00）语义是「当天 23:00 到次日 02:00」，同一段里 s &gt; e 即跨天，
     * 所以要额外检查昨天是否有段延续到今天凌晨。解析失败按「不在时段内」处理。
     */
    boolean inPlanWindow(String planJson, LocalDateTime now) {
        if (planJson == null || planJson.isBlank()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode days;
        try {
            days = new com.fasterxml.jackson.databind.ObjectMapper().readTree(planJson);
        } catch (Exception e) {
            log.debug("录像计划 JSON 解析失败（按不在时段内处理）: {}", e.getMessage());
            return false;
        }
        if (days == null || !days.isArray()) {
            return false;
        }
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        // 周一 = 0（LocalDateTime.getDayOfWeek 是 MONDAY=1..SUNDAY=7）
        int today = now.getDayOfWeek().getValue() - 1;
        int yesterday = (today + 6) % 7;

        // 1) 今天的时段（正常段 start < end；跨零点段的前半 start > end 且当前在 start 之后）
        if (matchDay(days, today, minuteOfDay, true)) {
            return true;
        }
        // 2) 昨天的跨零点段是否延续到今天
        return matchDay(days, yesterday, minuteOfDay, false);
    }

    /**
     * 判断某一天的时段是否命中当前时刻。
     *
     * @param primary {@code true} 处理「当天段」与「跨零点段的前半」；
     *                {@code false} 只处理「昨天跨零点段延续到今天凌晨」的部分
     */
    private boolean matchDay(com.fasterxml.jackson.databind.JsonNode days, int dayIndex,
                             int minute, boolean primary) {
        if (dayIndex < 0 || dayIndex >= days.size()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode segs = days.get(dayIndex);
        if (segs == null || !segs.isArray()) {
            return false;
        }
        // 键名里的数字是「第几天」（1~7）即下标 + 1；历史数据里可能键名与数组不匹配，故两者都试
        String sKey = "s" + (dayIndex + 1);
        String eKey = "e" + (dayIndex + 1);
        for (com.fasterxml.jackson.databind.JsonNode seg : segs) {
            int start = parseMinute(text(seg, sKey, "s1", "s2", "s3", "s4", "s5", "s6", "s7"));
            int end = parseMinute(text(seg, eKey, "e1", "e2", "e3", "e4", "e5", "e6", "e7"));
            if (start < 0 || end < 0) {
                continue;
            }
            if (start < end) {
                // 正常段 [start, end)
                if (primary && minute >= start && minute < end) {
                    return true;
                }
            } else if (start > end) {
                // 跨零点段：当天 [start, 24:00) + 次日 [00:00, end)
                if (primary && minute >= start) {
                    return true;
                }
                if (!primary && minute < end) {
                    return true;
                }
            }
            // start == end：空段（0 长度）。全天用 00:00~24:00 表示。
        }
        return false;
    }

    /** 依次尝试多个键名，返回第一个非空的文本；都没有则返回 {@code null}。 */
    private static String text(com.fasterxml.jackson.databind.JsonNode seg, String... keys) {
        for (String k : keys) {
            if (seg.hasNonNull(k) && !seg.get(k).asText().isBlank()) {
                return seg.get(k).asText();
            }
        }
        return null;
    }

    /** {@code "HH:MM"} → 当天第几分钟；{@code "24:00"} 视为 1440；非法返回 -1。 */
    private static int parseMinute(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return -1;
        }
        String v = hhmm.trim();
        int colon = v.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            int h = Integer.parseInt(v.substring(0, colon));
            int m = Integer.parseInt(v.substring(colon + 1));
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
