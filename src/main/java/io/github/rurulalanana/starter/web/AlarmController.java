package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbAlarm;
import io.github.rurulalanana.starter.service.AlarmService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.vo.AlarmListVo;
import io.github.rurulalanana.starter.vo.AlarmVo;
import io.github.rurulalanana.starter.vo.DeletedVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

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
@RequestMapping("/api/v1/alarm")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;
    private final DeviceService deviceService;

    /** 报警列表。 */
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
        List<GbAlarm> all = alarmService.list(serial, code, starttime, endtime, priority, method);
        List<AlarmVo> list = new ArrayList<>();
        for (GbAlarm a : all) {
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase();
                boolean hit = (a.getAlarmId() != null && a.getAlarmId().toLowerCase().contains(needle))
                        || (a.getChannelId() != null && a.getChannelId().toLowerCase().contains(needle))
                        || (a.getAlarmDescription() != null
                        && a.getAlarmDescription().toLowerCase().contains(needle));
                if (!hit) {
                    continue;
                }
            }
            list.add(alarmView(a));
        }
        AlarmListVo vo = new AlarmListVo();
        vo.setAlarmCount(list.size());
        vo.setAlarmReserveDays(30);
        vo.setAlarmPublishToRedis(false);
        vo.setAlarmList(limit > 0
                ? list.subList(Math.min(start, list.size()), Math.min(start + limit, list.size()))
                : list);
        return ApiResponse.ok(vo);
    }

    /** 单条报警。 */
    @GetMapping("/info")
    public ApiResponse<AlarmVo> info(@RequestParam String id) {
        StpUtil.checkLogin();
        GbAlarm alarm = alarmService.find(id);
        if (alarm == null) {
            throw BizException.notFound("报警不存在：" + id);
        }
        return ApiResponse.ok(alarmView(alarm));
    }

    /** 删除报警。 */
    @PostMapping("/remove")
    public ApiResponse<DeletedVo> remove(@RequestParam String id) {
        StpUtil.checkLogin();
        int deleted = alarmService.remove(id);
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    /** 清空报警。 */
    @PostMapping("/clear")
    public ApiResponse<DeletedVo> clear() {
        StpUtil.checkLogin();
        int deleted = alarmService.clear();
        return ApiResponse.ok(DeletedVo.of(deleted));
    }

    // ==================== 内部 ====================

    private AlarmVo alarmView(GbAlarm a) {
        AlarmVo vo = new AlarmVo();
        vo.setId(a.getAlarmId());
        vo.setDeviceId(a.getDeviceId());
        vo.setDeviceName(nameOf(a.getDeviceId()));
        vo.setChannelId(a.getChannelId());
        vo.setChannelName(channelNameOf(a.getChannelId()));
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

    private String nameOf(String deviceId) {
        var d = deviceService.findDevice(deviceId);
        return d == null ? null : d.displayName();
    }

    private String channelNameOf(String channelId) {
        var c = deviceService.findChannel(channelId);
        return c == null ? null : c.displayName();
    }
}
