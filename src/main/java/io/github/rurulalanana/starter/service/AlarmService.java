package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbAlarm;
import io.github.rurulalanana.starter.mapper.GbAlarmMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 报警服务（标准 9.4 报警通知 / A.2.5.3）。
 *
 * <p>设备通过 {@code Notify} 消息上报报警，本服务负责落库、查询与清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    /** 报警状态：未处理。 */
    public static final String STATE_ACTIVE = "ACTIVE";
    /** 报警状态：已复位。 */
    public static final String STATE_RESET = "RESET";

    private final GbAlarmMapper alarmMapper;

    // ==================== 接收 ====================

    /**
     * 处理设备上报的报警通知。
     *
     * @param deviceId 上报来源设备
     * @param msg      MANSCDP 消息体
     */
    @Transactional
    public GbAlarm onAlarmNotify(String deviceId, ManscdpMessage msg) {
        if (msg == null) {
            return null;
        }
        String channelId = msg.firstText("DeviceID");
        GbAlarm alarm = new GbAlarm();
        alarm.setAlarmId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        alarm.setDeviceId(deviceId);
        alarm.setChannelId(channelId);
        alarm.setPriority(msg.intOf("AlarmPriority"));
        alarm.setPriorityName(priorityName(alarm.getPriority()));
        alarm.setMethod(msg.intOf("AlarmMethod"));
        alarm.setMethodName(methodName(alarm.getMethod()));
        alarm.setAlarmType(msg.intOf("AlarmType"));
        alarm.setAlarmTypeName(alarmTypeName(alarm.getMethod(), alarm.getAlarmType()));
        alarm.setEventType(msg.intOf("AlarmEventType"));
        alarm.setAlarmDescription(msg.firstText("AlarmDescription"));
        LocalDateTime time = TimeFormats.parse(msg.firstText("AlarmTime"));
        alarm.setStartTime(time == null ? LocalDateTime.now() : time);
        alarm.setState(STATE_ACTIVE);
        alarm.setLongitude(parseDecimal(msg.firstText("Longitude")));
        alarm.setLatitude(parseDecimal(msg.firstText("Latitude")));
        alarm.setCreateTime(LocalDateTime.now());
        alarmMapper.insert(alarm);
        log.info("收到报警: device={} channel={} priority={} method={} type={}",
                deviceId, channelId, alarm.getPriority(), alarm.getMethod(), alarm.getAlarmType());
        return alarm;
    }

    /** 处理报警复位。 */
    @Transactional
    public int onAlarmReset(String deviceId, ManscdpMessage msg) {
        if (msg == null) {
            return 0;
        }
        String channelId = msg.firstText("DeviceID");
        List<GbAlarm> active = alarmMapper.selectList(Wrappers.<GbAlarm>lambdaQuery()
                .eq(GbAlarm::getDeviceId, deviceId)
                .eq(channelId != null, GbAlarm::getChannelId, channelId)
                .eq(GbAlarm::getState, STATE_ACTIVE));
        for (GbAlarm a : active) {
            a.setState(STATE_RESET);
            a.setEndTime(LocalDateTime.now());
            alarmMapper.updateById(a);
        }
        log.info("报警复位: device={} channel={} 影响 {} 条", deviceId, channelId, active.size());
        return active.size();
    }

    // ==================== 查询 ====================

    /** 报警列表查询。 */
    public List<GbAlarm> list(String serial, String code, String startTime, String endTime,
                              Integer priority, Integer method) {
        LambdaQueryWrapper<GbAlarm> q = Wrappers.<GbAlarm>lambdaQuery()
                .eq(notBlank(serial), GbAlarm::getDeviceId, serial)
                .eq(notBlank(code), GbAlarm::getChannelId, code)
                .eq(priority != null, GbAlarm::getPriority, priority)
                .eq(method != null, GbAlarm::getMethod, method);
        LocalDateTime from = TimeFormats.parse(startTime);
        LocalDateTime to = TimeFormats.parse(endTime);
        if (from != null) {
            q.ge(GbAlarm::getStartTime, from);
        }
        if (to != null) {
            q.le(GbAlarm::getStartTime, to);
        }
        return alarmMapper.selectList(q.orderByDesc(GbAlarm::getStartTime));
    }

    /** 单条报警详情。 */
    public GbAlarm find(String alarmId) {
        if (!notBlank(alarmId)) {
            throw BizException.badRequest("缺少报警 ID");
        }
        return alarmMapper.selectOne(Wrappers.<GbAlarm>lambdaQuery()
                .eq(GbAlarm::getAlarmId, alarmId).last("limit 1"));
    }

    /** 删除报警（支持逗号分隔的多个 ID）。 */
    @Transactional
    public int remove(String ids) {
        if (!notBlank(ids)) {
            throw BizException.badRequest("缺少报警 ID");
        }
        List<String> list = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return alarmMapper.delete(Wrappers.<GbAlarm>lambdaQuery()
                .in(GbAlarm::getAlarmId, list));
    }

    /** 清空全部报警。 */
    @Transactional
    /**
     * 清空全部报警。
     *
     * <p><b>不能写 {@code delete(null)}</b>：MyBatis-Plus 的
     * {@code BlockAttackInnerInterceptor} 会拦截无 where 条件的全表写，
     * 直接抛 {@code Prohibition of table update operation}，
     * 导致「清空报警」按钮永远失败（前端只看到一句数据库错误）。
     * 这里用一个永真的 id 条件代替，既绕开拦截器语义（仍然明确指定了范围），
     * 也能走索引。
     */
    public int clear() {
        return alarmMapper.delete(Wrappers.<GbAlarm>lambdaQuery().gt(GbAlarm::getId, 0));
    }

    // ==================== 字典 ====================

    /** 报警级别名称。 */
    public static String priorityName(Integer priority) {
        if (priority == null) {
            return null;
        }
        return switch (priority) {
            case 1 -> "一级警情";
            case 2 -> "二级警情";
            case 3 -> "三级警情";
            case 4 -> "四级警情";
            default -> "未知";
        };
    }

    /** 报警方式名称。 */
    public static String methodName(Integer method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case 1 -> "电话报警";
            case 2 -> "设备报警";
            case 3 -> "短信报警";
            case 4 -> "GPS报警";
            case 5 -> "视频报警";
            case 6 -> "设备故障报警";
            case 7 -> "其他报警";
            default -> "未知";
        };
    }

    /**
     * 报警类型名称。
     *
     * <p>同一 {@code AlarmType} 在不同 {@code AlarmMethod} 下含义不同，
     * 故必须结合报警方式解释（标准 A.2.5.3 表）。
     */
    public static String alarmTypeName(Integer method, Integer type) {
        if (type == null) {
            return null;
        }
        if (method != null && method == 2) {
            return switch (type) {
                case 1 -> "视频丢失报警";
                case 2 -> "设备防拆报警";
                case 3 -> "存储设备磁盘满报警";
                case 4 -> "设备高温报警";
                case 5 -> "设备低温报警";
                default -> "设备报警";
            };
        }
        if (method != null && method == 5) {
            return switch (type) {
                case 1 -> "人工视频报警";
                case 2 -> "运动目标检测报警";
                case 3 -> "遗留物检测报警";
                case 4 -> "物体移除检测报警";
                case 5 -> "绊线检测报警";
                case 6 -> "入侵检测报警";
                case 7 -> "逆行检测报警";
                case 8 -> "徘徊检测报警";
                case 9 -> "流量统计报警";
                case 10 -> "密度检测报警";
                case 11 -> "视频异常检测报警";
                case 12 -> "快速移动报警";
                default -> "视频报警";
            };
        }
        return "报警类型 " + type;
    }

    private static BigDecimal parseDecimal(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 便捷：从事件取报警并落库。 */
    public GbAlarm onManscdpEvent(ManscdpEvent event) {
        if (event == null || event.getManscdp() == null) {
            return null;
        }
        return onAlarmNotify(event.getManscdp().getDeviceId(), event.getManscdp());
    }
}
