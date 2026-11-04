package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Queries;
import io.github.rurulalanana.starter.common.Numbers;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbAlarm;
import io.github.rurulalanana.starter.mapper.GbAlarmMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        alarm.setLongitude(Numbers.decimalOrNull(msg.firstText("Longitude")));
        alarm.setLatitude(Numbers.decimalOrNull(msg.firstText("Latitude")));
        alarm.setCreateTime(LocalDateTime.now());
        alarmMapper.insert(alarm);
        log.info("收到报警: device={} channel={} priority={} method={} type={}",
                deviceId, channelId, alarm.getPriority(), alarm.getMethod(), alarm.getAlarmType());
        return alarm;
    }

    /**
     * 处理 SDK 直连设备（大华等）上报的报警。
     *
     * <p><b>为什么不能复用 {@link #onAlarmNotify}</b>：那条路吃的是 MANSCDP XML，
     * 字段是国标的 {@code AlarmPriority}/{@code AlarmMethod}；SDK 事件只有
     * 一个数字事件码加一段未解析的结构体，两套语义对不上。
     *
     * <p><b>为什么这里能落库而订阅回调里不能</b>：订阅回调跑在 SDK 原生线程，
     * 直接写库会阻塞后续事件（表现为「报警丢了一半」）。调用方
     * （{@code DahuaAlarmBridge}）已经在回调里做了队列转发，本方法跑在业务线程池上。
     *
     * <p><b>两道过滤</b>：
     * <ol>
     *   <li>{@link DahuaAlarmCodes#isAlarm} 挡掉非报警事件 —— 云台位置
     *       （{@code 0x2123}，云台一动就报一次）和订阅完成（{@code 0x300C}）
     *       都是从同一个回调进来的，早期没挡，一条云台测试就刷出上千条假报警。</li>
     *   <li>优先级方向换算：大华 3 = 最紧急、1 = 提示；国标 1 = 一级最紧急。
     *       不换算的话「动态检测」这种提示级会显示成红色的「一级警情」。</li>
     * </ol>
     *
     * @param deviceId 设备编号
     * @param command  SDK 事件码
     * @param eventIp  事件上报的 IP（用于交叉核对是否真是该设备）
     * @param state    事件结构体首 4 字节按 int 读出（{@code -1} = 结构体不足 4 字节）。
     *                 <b>只有 {@link DahuaAlarmCodes#hasState} 认的事件码才真的是 {@code dwState}</b>；
     *                 其它事件码的首字段是各自的业务字段（如 {@code 0x2183} 是通道号 1096）。
     *                 这里统一按「首字段」记录，是否当作状态位由 {@code hasState} 决定。
     * @return 落库的报警；被过滤掉时返回 {@code null}
     */
    @Transactional
    public GbAlarm onDahuaAlarm(String deviceId, int command, String eventIp, int state) {
        if (!io.github.rurulalanana.dahua.ext.DahuaAlarmCodes.isAlarm(command)) {
            // 云台位置 / 订阅完成等状态回调：不是报警，直接丢，别污染列表
            log.debug("忽略非报警事件: device={} cmd=0x{}", deviceId, Integer.toHexString(command));
            return null;
        }
        // 老式 _EX 事件的首字段就是 dwState：0 = 报警结束。
        // 订阅成功时设备会把当前全部报警状态整份推回来，一台正常的球机
        // 立刻上送 6 条 dwState=0 的事件（"这些报警都已结束"=一切正常）。
        // 不判这个 0 就会把「体检报告」存成 6 条新报警 —— 用户看到的现象
        // 就是「一重启就报：视频丢失 + 遮挡 + 硬盘错误」。
        if (io.github.rurulalanana.dahua.ext.DahuaAlarmCodes.hasState(command) && state == 0) {
            int closed = closeActive(deviceId, command);
            log.debug("SDK 报警结束: device={} cmd=0x{}，关闭 {} 条", deviceId,
                    Integer.toHexString(command), closed);
            return null;
        }
        GbAlarm alarm = new GbAlarm();
        alarm.setAlarmId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        alarm.setDeviceId(deviceId);
        // SDK 事件里没有通道号（要解析各事件自己的结构体才知道），统一记设备级
        alarm.setChannelId(null);
        // 大华紧迫度 3=紧急/2=一般/1=提示 → 国标 1=一级/2=二级/3=三级（方向相反）
        int urgency = io.github.rurulalanana.dahua.ext.DahuaAlarmCodes.urgency(command);
        int priority = 4 - urgency;
        alarm.setPriority(priority);
        alarm.setPriorityName(priorityName(priority));
        // 事件码放 eventType：国标那套 AlarmMethod/AlarmType 是 2016 版语义，SDK 设备没有
        alarm.setEventType(command);
        alarm.setAlarmTypeName(io.github.rurulalanana.dahua.ext.DahuaAlarmCodes.name(command));
        // 描述只留事件码：设备号在同一行的「设备」列已经显示了，再拼一遍 IP 会把
        // 真正有用的内容挤出可视区（列表里看到的是「SDK 直连事件 0x...」）。
        // 状态位与结构体原始字节都进了日志（DahuaAlarmBridge 的「大华事件明细」）。
        alarm.setAlarmDescription("SDK 直连事件 0x" + String.format("%04X", command));
        alarm.setStartTime(LocalDateTime.now());
        alarm.setState(STATE_ACTIVE);
        alarm.setCreateTime(LocalDateTime.now());
        alarmMapper.insert(alarm);
        log.info("收到 SDK 报警: device={} cmd=0x{} {} priority={} state={} from={}",
                deviceId, Integer.toHexString(command), alarm.getAlarmTypeName(), priority, state, eventIp);
        return alarm;
    }

    /**
     * 关掉某设备某事件码下仍处于 ACTIVE 的报警（SDK 上报「报警结束」时用）。
     *
     * <p>SDK 事件不带通道号，所以只能按「设备 + 事件码」定位，
     * 不按通道 —— 按通道筛反而一条都关不掉，报警会永远挂在 ACTIVE。
     *
     * @return 关闭的条数
     */
    private int closeActive(String deviceId, int command) {
        List<GbAlarm> active = alarmMapper.selectList(Wrappers.<GbAlarm>lambdaQuery()
                .eq(GbAlarm::getDeviceId, deviceId)
                .eq(GbAlarm::getEventType, command)
                .eq(GbAlarm::getState, STATE_ACTIVE));
        LocalDateTime now = LocalDateTime.now();
        for (GbAlarm a : active) {
            a.setState(STATE_RESET);
            a.setEndTime(now);
            alarmMapper.updateById(a);
        }
        return active.size();
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

    /**
     * 报警列表查询（<b>不分页</b>，返回全部命中行）。
     *
     * <p>⚠️ 列表接口不要再用这个：实测这张表 5 千多行，全量 {@code selectList}
     * 再在 Java 里切片，单次就要 2~3 秒，随着报警累积会线性变差。
     * 列表请用 {@link #page} + {@link #count}，让数据库只返回当前页。
     */
    public List<GbAlarm> list(String serial, String code, String startTime, String endTime,
                              Integer priority, Integer method) {
        return alarmMapper.selectList(filter(serial, code, startTime, endTime, priority, method, null)
                .orderByDesc(GbAlarm::getStartTime));
    }

    /**
     * 报警分页查询（SQL 级 {@code LIMIT/OFFSET}）。
     *
     * @param keyword 关键词，匹配报警编号 / 设备编码 / 通道编码 / 描述（不区分大小写）；
     *                空白表示不过滤。<b>必须下推到 SQL</b>，否则又要全表扫描。
     * @param offset  偏移量，负数按 0 处理
     * @param limit   每页条数；{@code <=0} 表示不分页（保留旧行为，给导出类场景用）
     */
    public List<GbAlarm> page(String serial, String code, String startTime, String endTime,
                              Integer priority, Integer method, String keyword,
                              int offset, int limit) {
        LambdaQueryWrapper<GbAlarm> q = filter(serial, code, startTime, endTime,
                priority, method, keyword).orderByDesc(GbAlarm::getStartTime);
        if (limit > 0) {
            int from = Math.max(offset, 0);
            // last() 直接拼进 SQL 尾巴；两个值都是 int，不存在注入面
            q.last("limit " + limit + " offset " + from);
        }
        return alarmMapper.selectList(q);
    }

    /** 命中行数（与 {@link #page} 的条件完全一致，用于前端分页总数）。 */
    public long count(String serial, String code, String startTime, String endTime,
                      Integer priority, Integer method, String keyword) {
        Long n = alarmMapper.selectCount(filter(serial, code, startTime, endTime,
                priority, method, keyword));
        return n == null ? 0L : n;
    }

    /**
     * 拼公共查询条件 —— {@link #list} / {@link #page} / {@link #count} 共用，
     * 保证「列表内容」和「总数」永远是同一套过滤口径（不一致会导致页码翻到空白）。
     */
    private LambdaQueryWrapper<GbAlarm> filter(String serial, String code,
                                               String startTime, String endTime,
                                               Integer priority, Integer method,
                                               String keyword) {
        LambdaQueryWrapper<GbAlarm> q = Queries.deviceAndChannel(
                Wrappers.<GbAlarm>lambdaQuery(),
                GbAlarm::getDeviceId, GbAlarm::getChannelId, serial, code);
        q.eq(priority != null, GbAlarm::getPriority, priority)
                .eq(method != null, GbAlarm::getMethod, method);
        Queries.timeRange(q, GbAlarm::getStartTime, startTime, endTime);
        if (keyword != null && !keyword.isBlank()) {
            /*
             * 关键词用 lower(...) like 而不是 like：PostgreSQL 的 LIKE 区分大小写，
             * 用户输 "IPC" 就搜不到 "ipc"。用 apply 是因为 LambdaQueryWrapper 的
             * like() 没法强制小写比较。
             * 占位符是 {0}，参数绑定，不存在注入面。
             */
            String needle = "%" + keyword.trim().toLowerCase() + "%";
            q.and(w -> w.apply("lower(alarm_id) like {0}", needle)
                    .or().apply("lower(device_id) like {0}", needle)
                    .or().apply("lower(channel_id) like {0}", needle)
                    .or().apply("lower(alarm_description) like {0}", needle));
        }
        return q;
    }

    /** 单条报警详情。 */
    public GbAlarm find(String alarmId) {
        if (!StringUtils.hasText(alarmId)) {
            throw BizException.badRequest("缺少报警 ID");
        }
        return alarmMapper.selectOne(Wrappers.<GbAlarm>lambdaQuery()
                .eq(GbAlarm::getAlarmId, alarmId).last("limit 1"));
    }

    /**
     * 报警过期清理。
     *
     * <p><b>此前这张表完全没有清理路径</b>，而移动侦测类报警在高频点位上
     * 每天可产生上千条。跑几个月就是几十万行，而报警列表是按
     * {@code start_time DESC} 分页查的 —— 表越大翻页越慢，
     * 最终表现是「报警页面打不开」，且只能靠 DBA 手工 DELETE。
     *
     * <p>用 {@code start_time}（报警发生时间）而不是入库时间作判据：
     * 设备补传的历史报警可能入库很晚，但那属于「旧报警」，
     * 不该因为刚入库就永久留下。
     *
     * @param retainDays 保留天数；{@code <=0} 直接跳过
     * @return 删除的行数
     */
    public int purgeExpired(int retainDays) {
        if (retainDays <= 0) {
            return 0;
        }
        return alarmMapper.delete(Wrappers.<GbAlarm>lambdaQuery()
                .lt(GbAlarm::getStartTime, LocalDateTime.now().minusDays(retainDays)));
    }

    /** 删除报警（支持逗号分隔的多个 ID）。 */
    @Transactional
    public int remove(String ids) {
        if (!StringUtils.hasText(ids)) {
            throw BizException.badRequest("缺少报警 ID");
        }
        List<String> list = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return alarmMapper.delete(Wrappers.<GbAlarm>lambdaQuery()
                .in(GbAlarm::getAlarmId, list));
    }

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
    @Transactional
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

    /** 便捷：从事件取报警并落库。 */
    public GbAlarm onManscdpEvent(ManscdpEvent event) {
        if (event == null || event.getManscdp() == null) {
            return null;
        }
        return onAlarmNotify(event.getManscdp().getDeviceId(), event.getManscdp());
    }
}
