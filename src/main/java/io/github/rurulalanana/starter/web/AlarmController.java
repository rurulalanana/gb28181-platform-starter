package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbAlarm;
import io.github.rurulalanana.starter.service.AlarmService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.vo.AlarmListVo;
import io.github.rurulalanana.starter.vo.AlarmVo;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 设备报警接口。
 *
 * <pre>
 * GET  /api/v1/alarm/list     报警列表
 * GET  /api/v1/alarm/info     单条报警
 * POST /api/v1/alarm/remove   删除报警
 * POST /api/v1/alarm/clear    清空报警
 * </pre>
 *
 * <p>报警来源：设备通过 {@code Notify/Alarm} 主动上报，由 {@code SipEventBridge} 落库。
 */
@Slf4j
@RestController
@Tag(name = "报警管理", description = "报警记录查询与处置")
@RequestMapping("/api/v1/alarm")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;
    private final DeviceService deviceService;

    /**
     * 报警列表。
     *
     * <h3>性能：曾经 5819 条报警要跑 290 秒</h3>
     * 原实现对<b>全部</b>报警逐条调用 {@code nameOf}/{@code channelNameOf} 补设备名、
     * 通道名，也就是每条 2 次数据库往返。实测 5819 条 × 2 = 11638 次跨网查询，
     * 单次约 25ms → 约 290 秒，前端请求直接超时，页面「加载不出来」。
     *
     * <p>更讽刺的是：报警表格只显示 {@code deviceId}/{@code channelId}，
     * 那两个名字前端一个字段都没用到。
     *
     * <p>现在改成三步，查询次数与总行数无关：
     * <ol>
     *   <li>关键词过滤在<b>实体</b>上做（不建 VO，零额外查询）；</li>
     *   <li>先切片，只对当前页建 VO；</li>
     *   <li>设备名/通道名用一次 {@code IN} 批量取回（{@link DeviceService#deviceNames}）。</li>
     * </ol>
     * 无论表里 6 千行还是 60 万行，这个接口固定 3 次数据库查询。
     */
    @Operation(summary = "报警列表")
    @GetMapping("/list")
    public ApiResponse<AlarmListVo> list(
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer method,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q) {
        StpUtil.checkLogin();
        // ③ 分页与关键词全部下推到 SQL：只取当前页，不再全表扫描
        long total = alarmService.count(serial, code, starttime, endtime, priority, method, q);
        List<GbAlarm> page = alarmService.page(serial, code, starttime, endtime,
                priority, method, q, start, limit);

        // 设备名/通道名一次批量取回（固定 2 次查询，与行数无关）
        List<String> devIds = new ArrayList<>(page.size());
        List<String> chIds = new ArrayList<>(page.size());
        for (GbAlarm a : page) {
            devIds.add(a.getDeviceId());
            chIds.add(a.getChannelId());
        }
        Map<String, String> devNames = deviceService.deviceNames(devIds);
        Map<String, String> chNames = deviceService.channelNames(chIds);

        List<AlarmVo> list = new ArrayList<>(page.size());
        for (GbAlarm a : page) {
            list.add(alarmView(a, devNames, chNames));
        }
        AlarmListVo vo = new AlarmListVo();
        vo.setAlarmCount((int) Math.min(total, Integer.MAX_VALUE));
        vo.setAlarmReserveDays(30);
        vo.setAlarmPublishToRedis(false);
        vo.setAlarmList(list);
        return ApiResponse.ok(vo);
    }

    /**
     * 单条报警。
     *
     * <p>单条不需要批量，但仍走同一套建 VO 的方法，避免两处字段不一致。
     */
    @Operation(summary = "单条报警")
    @GetMapping("/info")
    public ApiResponse<AlarmVo> info(@RequestParam String id) {
        StpUtil.checkLogin();
        GbAlarm alarm = alarmService.find(id);
        if (alarm == null) {
            throw BizException.notFound("报警不存在：" + id);
        }
        // 单条也批量取名字。用 Collections.singletonList 而不是 List.of：
        // SDK 上报的报警没有通道号（channelId 为 null），而 List.of(null) 直接抛 NPE。
        Map<String, String> devNames = deviceService.deviceNames(
                Collections.singletonList(alarm.getDeviceId()));
        Map<String, String> chNames = deviceService.channelNames(
                Collections.singletonList(alarm.getChannelId()));
        return ApiResponse.ok(alarmView(alarm, devNames, chNames));
    }
    /** 删除报警。 */
    @Operation(summary = "删除报警")
    @PostMapping("/remove")
    public ApiResponse<DeletedVo> remove(@RequestParam String id) {
        StpUtil.checkLogin();
        int deleted = alarmService.remove(id);
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    /** 清空报警。 */
    @Operation(summary = "清空报警")
    @PostMapping("/clear")
    public ApiResponse<DeletedVo> clear() {
        // 清空是破坏性操作：误点会丢掉全部历史警情，需要独立的 alarm:delete
        StpUtil.checkPermission(Permissions.ALARM_DELETE);
        int deleted = alarmService.clear();
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    // ==================== 内部 ====================

    /**
     * 实体 → VO。
     *
     * <p>设备名/通道名<b>只从传入的 Map 里取</b>，绝不在这里查库：
     * 这个方法一页要跑几十次，任何一次查询都会被放大成 N+1。
     * 调用方负责先批量取好（见 {@link #list}）。
     */
    private AlarmVo alarmView(GbAlarm a,
                              Map<String, String> deviceNames,
                              Map<String, String> channelNames) {
        AlarmVo vo = new AlarmVo();
        vo.setId(a.getAlarmId());
        vo.setDeviceId(a.getDeviceId());
        vo.setDeviceName(deviceNames.get(a.getDeviceId()));
        vo.setChannelId(a.getChannelId());
        vo.setChannelName(channelNames.get(a.getChannelId()));
        vo.setAlarmPriority(a.getPriority());
        vo.setAlarmPriorityName(a.getPriorityName());
        vo.setAlarmMethod(a.getMethod());
        vo.setAlarmMethodName(a.getMethodName());
        vo.setAlarmType(a.getAlarmType());
        vo.setAlarmTypeName(a.getAlarmTypeName());
        vo.setAlarmEventType(a.getEventType());
        vo.setAlarmDescription(a.getAlarmDescription());
        vo.setTime(TimeFormats.toDisplay(a.getStartTime()));
        vo.setEndTime(TimeFormats.toDisplay(a.getEndTime()));
        vo.setState(a.getState());
        vo.setLongitude(a.getLongitude());
        vo.setLatitude(a.getLatitude());
        vo.setRecordPath(a.getVideoPath());
        vo.setSnapPath(a.getSnapPath());
        vo.setCreatedAt(TimeFormats.toDisplay(a.getCreateTime()));
        return vo;
    }
}
