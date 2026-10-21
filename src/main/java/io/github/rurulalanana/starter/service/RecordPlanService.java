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
}
