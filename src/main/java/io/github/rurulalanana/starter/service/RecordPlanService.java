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
 * 录像计划服务。
 *
 * <p>计划 = 「哪些通道、在每周哪几天、每天什么时段录云端录像」。
 * 数据结构与 LiveGBS 兼容（{@code plan} 存 JSON，7 个数组对应周一~周日）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordPlanService {

    private final GbRecordPlanMapper planMapper;
    private final GbRecordPlanChannelMapper planChannelMapper;
    private final DeviceService deviceService;

    /** 全部计划（按创建时间倒序）。 */
    public List<GbRecordPlan> listAll() {
        return planMapper.selectList(Wrappers.<GbRecordPlan>lambdaQuery()
                .orderByDesc(GbRecordPlan::getCreateTime));
    }

    /** 按 ID 取计划。 */
    public GbRecordPlan find(String planId) {
        if (planId == null || planId.isBlank()) {
            return null;
        }
        return planMapper.selectOne(Wrappers.<GbRecordPlan>lambdaQuery()
                .eq(GbRecordPlan::getPlanId, planId).last("limit 1"));
    }

    /**
     * 新增或更新计划。
     *
     * @param planId 为空则新建
     * @param name   计划名称
     * @param plan   时段规则 JSON 字符串
     * @param enable 是否启用
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

    /** 启用/停用计划。 */
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

    /** 删除计划（连同通道关联）。 */
    @Transactional
    public void remove(String planId) {
        planMapper.delete(Wrappers.<GbRecordPlan>lambdaQuery().eq(GbRecordPlan::getPlanId, planId));
        planChannelMapper.delete(Wrappers.<GbRecordPlanChannel>lambdaQuery()
                .eq(GbRecordPlanChannel::getPlanId, planId));
    }

    /** 计划已关联的通道编码。 */
    public Set<String> channelIds(String planId) {
        Set<String> ids = new HashSet<>();
        for (GbRecordPlanChannel c : planChannelMapper.selectList(
                Wrappers.<GbRecordPlanChannel>lambdaQuery().eq(GbRecordPlanChannel::getPlanId, planId))) {
            ids.add(c.getChannelId());
        }
        return ids;
    }

    /**
     * 设置计划的关联通道（全量覆盖）。
     *
     * @param planId 计划 ID
     * @param refs   通道引用，元素形如 {@code <设备编码>/<通道编码>}
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
     * @param active   此刻<b>应在录制</b>的通道编码集合
     * @param governed 被任一启用计划<b>覆盖</b>的通道编码集合（含此刻不该录的）
     */
    public record PlanScope(Set<String> active, Set<String> governed) {
        /** 被计划覆盖、但此刻不该录的通道。 */
        public Set<String> shouldStop() {
            Set<String> out = new HashSet<>(governed);
            out.removeAll(active);
            return out;
        }
    }

    /**
     * 计算此刻的计划执行范围。
     *
     * <p><b>为什么需要它</b>：本类原先只提供 CRUD，<b>没有任何地方真正执行计划</b>——
     * 用户在界面上配好时段、关联通道后什么都不会发生。这是功能缺失，不是配置问题。
     *
     * <p><b>语义：计划对覆盖的通道有完全控制权</b>（与 LiveGBS 一致）：
     * <ul>
     *   <li>时段内 → 云端录像应为「开」；</li>
     *   <li>时段外 → 云端录像应为「关」（即使之前是手动开的）。</li>
     * </ul>
     * 这样行为可预期：关联到计划的通道，开关由计划决定。
     * 界面需明确提示这一点，否则用户会困惑「我手动开的怎么自己关了」。
     *
     * @param now 计算基准时刻（便于测试与「跨零点」判断）
     */
    public PlanScope scopeAt(LocalDateTime now) {
        Set<String> active = new HashSet<>();
        Set<String> governed = new HashSet<>();
        // 一次性取出「启用计划 → 通道」的映射，避免逐计划查库
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
            if (inPlanWindow(plan.getPlan(), now)) {
                active.add(c.getChannelId());
            }
        }
        return new PlanScope(active, governed);
    }

    /**
     * 判断某时刻是否落在计划时段内。
     *
     * <p>计划 JSON 结构（与 LiveGBS 兼容）：
     * {@code [[{"s1":"00:00","e1":"24:00"}], [], ..., []]}，
     * 外层 7 个数组依次为<b>周一 ~ 周日</b>，键名里的数字即「第几天」（1~7）。
     *
     * <p><b>跨零点时段的处理</b>（如 {@code 23:00 ~ 02:00}）：
     * 国标/GBS 的语义是「从当天 23:00 到次日 02:00」，同一段里
     * {@code s > e} 即表示跨天。因此判断时除了看今天的段，
     * 还要看<b>昨天</b>是否有段跨到了今天凌晨。
     *
     * <p>解析失败按「不在时段内」处理（宁可少录也不要因为脏数据一直录）。
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

        // 1) 今天的时段（正常段：start < end；或跨零点段的前半：start > end 且当前在 start 之后）
        if (matchDay(days, today, minuteOfDay, true)) {
            return true;
        }
        // 2) 昨天的跨零点段是否延续到今天
        return matchDay(days, yesterday, minuteOfDay, false);
    }

    /**
     * 判断某一天的时段是否命中当前时刻。
     *
     * @param dayIndex 数组下标（0=周一）
     * @param minute   当天第几分钟
     * @param primary  是否是「今天」这个数组。
     *                 {@code true} 时处理「当天段」与「跨零点段的前半」；
     *                 {@code false} 时只处理「昨天跨零点段延续到今天凌晨」的部分
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
        // 键名里的数字是「第几天」（1~7），即数组下标 + 1。
        // 历史数据里可能存在「键名与所在数组不匹配」的脏数据，
        // 故同时尝试按当前数组下标推导的键名，两者取到哪个用哪个。
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
