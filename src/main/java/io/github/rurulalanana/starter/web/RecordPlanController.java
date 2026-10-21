package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbRecordPlan;
import io.github.rurulalanana.starter.service.RecordPlanService;
import io.github.rurulalanana.starter.vo.RecordPlanListVo;
import io.github.rurulalanana.starter.vo.RecordPlanVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 录像计划接口（{@code /api/v1/cloudrecord/plan/*}）。
 *
 * <p>计划定义「哪些通道、在每周哪几天、每天什么时段录云端录像」。
 * 结构与 LiveGBS 的录像计划一致：{@code plan} 存 JSON，
 * 外层 7 个数组对应周一~周日，每项是该天的时段列表。
 *
 * <pre>
 * GET  /api/v1/cloudrecord/plan/list       计划列表
 * POST /api/v1/cloudrecord/plan/save       新增/修改计划
 * POST /api/v1/cloudrecord/plan/enable     启用/停用
 * POST /api/v1/cloudrecord/plan/remove     删除计划
 * GET  /api/v1/cloudrecord/plan/channellist 可关联的通道
 * POST /api/v1/cloudrecord/plan/setchannels 设置关联通道
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cloudrecord/plan")
@RequiredArgsConstructor
public class RecordPlanController {

    private final RecordPlanService planService;

    /** 计划列表。 */
    @GetMapping("/list")
    public ApiResponse<RecordPlanListVo> list(@RequestParam(required = false) String q) {
        StpUtil.checkLogin();
        List<RecordPlanVo> rows = new ArrayList<>();
        for (GbRecordPlan p : planService.listAll()) {
            if (q != null && !q.isBlank() && !p.getName().toLowerCase().contains(q.trim().toLowerCase())) {
                continue;
            }
            RecordPlanVo vo = new RecordPlanVo();
            vo.setID(p.getPlanId());
            vo.setName(p.getName());
            vo.setPlan(p.getPlan());
            vo.setEnable(p.getEnable());
            vo.setCreatedAt(TimeFormats.toDisplay(p.getCreateTime()));
            vo.setUpdatedAt(TimeFormats.toDisplay(p.getUpdateTime()));
            vo.setChannelCount(planService.channelIds(p.getPlanId()).size());
            rows.add(vo);
        }
        RecordPlanListVo res = new RecordPlanListVo();
        res.setTotal(rows.size());
        res.setRows(rows);
        return ApiResponse.ok(res);
    }

    /** 新增或修改计划。 */
    @PostMapping("/save")
    public ApiResponse<String> save(@RequestParam(required = false) String id,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String plan,
                                    @RequestParam(defaultValue = "true") Boolean enable) {
        StpUtil.checkLogin();
        return ApiResponse.ok(planService.save(id, name, plan, enable));
    }

    /** 启用/停用计划。 */
    @PostMapping("/enable")
    public ApiResponse<Void> enable(@RequestParam String id,
                                    @RequestParam(defaultValue = "true") Boolean enable) {
        StpUtil.checkLogin();
        planService.setEnable(id, Boolean.TRUE.equals(enable));
        return ApiResponse.ok();
    }

    /** 删除计划。 */
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String id) {
        StpUtil.checkLogin();
        planService.remove(id);
        return ApiResponse.ok();
    }

    /**
     * 可关联的通道列表（供「关联通道」弹窗用）。
     *
     * @param related 只看该计划已关联的通道
     */
    @GetMapping("/channellist")
    public ApiResponse<List<ChannelRefVo>> channelList(@RequestParam(required = false) String id,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(defaultValue = "false") Boolean related) {
        StpUtil.checkLogin();
        var relatedIds = related && id != null ? planService.channelIds(id) : java.util.Set.<String>of();
        List<ChannelRefVo> rows = new ArrayList<>();
        for (GbChannel c : planService.candidateChannels(q)) {
            if (related && !relatedIds.contains(c.getChannelId())) {
                continue;
            }
            ChannelRefVo vo = new ChannelRefVo();
            vo.setDeviceId(c.getDeviceId());
            vo.setChannelId(c.getChannelId());
            vo.setName(c.displayName());
            vo.setStatus(c.getStatus());
            vo.setOnline("ON".equals(c.getStatus()));
            rows.add(vo);
        }
        return ApiResponse.ok(rows);
    }

    /** 设置计划的关联通道（全量覆盖）。 */
    @PostMapping("/setchannels")
    public ApiResponse<Void> setChannels(@RequestParam String id,
                                         @RequestParam(required = false) List<String> channels) {
        StpUtil.checkLogin();
        if (planService.find(id) == null) {
            throw BizException.notFound("录像计划不存在：" + id);
        }
        planService.setChannels(id, channels);
        return ApiResponse.ok();
    }

    /** 通道引用（设备 + 通道 + 名称 + 状态）。 */
    @lombok.Data
    public static class ChannelRefVo {
        private String deviceId;
        private String channelId;
        private String name;
        private String status;
        private Boolean online;
    }
}
