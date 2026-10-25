package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.entity.GbOperationLog;
import io.github.rurulalanana.starter.mapper.GbOperationLogMapper;
import io.github.rurulalanana.starter.vo.OpLogVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作日志服务：记录管理接口调用、分页查询、清空与过期清理。
 *
 * <p>参考 LiveGBS 的「操作日志」：记录调用方式（HTTP GET/POST）、资源路径、
 * 远端地址、结果、用时与操作人，并把资源路径映射成中文操作名，
 * 便于排查「谁在什么时候调了什么」。
 *
 * <p>写入失败绝不向上抛（日志是旁路功能，不能影响正常请求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final GbOperationLogMapper logMapper;

    /**
     * 资源路径 → 中文操作名（精确匹配）。
     *
     * <p>命名对齐 LiveGBS 的口径（「登录」「云台控制」「更新通道」等）。
     * 未收录的路径按前缀归并（见 {@link #OPERATION_PREFIXES}），
     * 仍无匹配则省略操作名（前端显示空）。
     */
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("/api/v1/login", "登录"),
            Map.entry("/api/v1/logout", "退出登录"),
            Map.entry("/api/v1/userinfo", "用户信息"),
            Map.entry("/api/v1/getserverinfo", "服务器信息"),
            Map.entry("/api/v1/modifypassword", "修改密码"),
            Map.entry("/api/v1/restart", "重启服务"),
            Map.entry("/api/v1/device/list", "设备列表"),
            Map.entry("/api/v1/device/info", "设备详情"),
            Map.entry("/api/v1/device/channellist", "通道列表"),
            Map.entry("/api/v1/device/channelinfo", "通道详情"),
            Map.entry("/api/v1/device/channeltree", "通道树"),
            Map.entry("/api/v1/device/grouptree", "设备分组树"),
            Map.entry("/api/v1/device/collect", "更新通道"),
            Map.entry("/api/v1/device/fetchcatalog", "查询目录"),
            Map.entry("/api/v1/device/fetchinfo", "查询设备信息"),
            Map.entry("/api/v1/device/fetchstatus", "查询设备状态"),
            Map.entry("/api/v1/device/fetchconfig", "查询设备配置"),
            Map.entry("/api/v1/device/fetchpreset", "查询预置位"),
            Map.entry("/api/v1/device/positionlog", "位置历史"),
            Map.entry("/api/v1/device/statuslog", "状态历史"),
            Map.entry("/api/v1/device/streamlog", "推流历史"),
            Map.entry("/api/v1/device/remove", "删除设备"),
            Map.entry("/api/v1/device/setname", "修改设备名称"),
            Map.entry("/api/v1/device/setinfo", "编辑设备"),
            Map.entry("/api/v1/device/setmediatransport", "设置流传输模式"),
            Map.entry("/api/v1/device/setchannelname", "修改通道名称"),
            Map.entry("/api/v1/device/setchannelptztype", "修改云台类型"),
            Map.entry("/api/v1/device/setchannelondemand", "通道按需拉流"),
            Map.entry("/api/v1/device/setchannelcloudrecord", "通道云端录像"),
            Map.entry("/api/v1/stream/start", "开始点播"),
            Map.entry("/api/v1/stream/stop", "停止点播"),
            Map.entry("/api/v1/stream/list", "流列表"),
            Map.entry("/api/v1/stream/relay/start", "开始转发"),
            Map.entry("/api/v1/stream/relay/list", "转发列表"),
            Map.entry("/api/v1/stream/relay/stop", "停止转发"),
            Map.entry("/api/v1/playback/recordlist", "录像文件列表"),
            Map.entry("/api/v1/playback/start", "开始回放"),
            Map.entry("/api/v1/playback/stop", "停止回放"),
            Map.entry("/api/v1/control/ptz", "云台控制"),
            Map.entry("/api/v1/control/ptzprecise", "云台精确控制"),
            Map.entry("/api/v1/control/fi", "光圈控制"),
            Map.entry("/api/v1/control/preset", "预置位操作"),
            Map.entry("/api/v1/control/dragzoomin", "拉框放大"),
            Map.entry("/api/v1/control/dragzoomout", "拉框缩小"),
            Map.entry("/api/v1/record/start", "开始录像"),
            Map.entry("/api/v1/record/stop", "停止录像"),
            Map.entry("/api/v1/record/list", "录像列表"),
            Map.entry("/api/v1/cloudrecord/querychannels", "云端录像通道"),
            Map.entry("/api/v1/cloudrecord/querymonthly", "云端录像月份"),
            Map.entry("/api/v1/cloudrecord/querydaily", "云端录像查询"),
            Map.entry("/api/v1/cloudrecord/remove", "删除云端录像"),
            Map.entry("/api/v1/alarm/list", "报警列表"),
            Map.entry("/api/v1/alarm/remove", "删除报警"),
            Map.entry("/api/v1/alarm/clear", "清空报警"),
            Map.entry("/api/v1/user/list", "用户列表"),
            Map.entry("/api/v1/user/add", "新增用户"),
            Map.entry("/api/v1/user/setinfo", "编辑用户"),
            Map.entry("/api/v1/user/remove", "删除用户"),
            Map.entry("/api/v1/user/resetpassword", "重置密码")
    );

    /** 前缀兵底：精确表未命中时按段级路径归并（长前缀在前，先匹配）。 */
    private static final Map<String, String> OPERATION_PREFIXES = Map.ofEntries(
            Map.entry("/api/v1/cascade", "级联操作"),
            Map.entry("/api/v1/device", "设备操作"),
            Map.entry("/api/v1/stream", "流媒体操作"),
            Map.entry("/api/v1/playback", "回放操作"),
            Map.entry("/api/v1/control", "设备控制"),
            Map.entry("/api/v1/cloudrecord", "云端录像"),
            Map.entry("/api/v1/record", "录像操作"),
            Map.entry("/api/v1/alarm", "报警操作"),
            Map.entry("/api/v1/user", "用户操作")
    );

    /** 从资源路径推断中文操作名（未识别返回 null）。 */
    public static String operationNameOf(String resource) {
        if (resource == null || resource.isEmpty()) {
            return null;
        }
        String exact = OPERATIONS.get(resource);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : OPERATION_PREFIXES.entrySet()) {
            if (resource.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 记录一条操作日志（同步写，失败仅记 warn）。 */
    public void record(String method, String resource, String remoteAddr, String result,
                       long durationMs, String operator) {
        record(method, resource, remoteAddr, result, durationMs, operator, null);
    }

    /**
     * 记录一条操作日志（含请求参数）。
     *
     * @param params 已脱敏、已截断的请求参数；可为 {@code null}
     */
    public void record(String method, String resource, String remoteAddr, String result,
                       long durationMs, String operator, String params) {
        try {
            GbOperationLog entry = new GbOperationLog();
            entry.setMethod(method);
            entry.setResource(resource);
            entry.setRemoteAddr(remoteAddr);
            entry.setResult(result);
            entry.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            entry.setOperator(operator);
            entry.setParams(params);
            entry.setCreateTime(LocalDateTime.now());
            logMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写入操作日志失败：{} {} -> {}", method, resource, e.toString());
        }
    }

    /** 分页查询（游标语义：start 偏移 + limit 页大小，limit=0 不分页）。 */
    public List<OpLogVo> list(String method, String keyword, LocalDateTime begin, LocalDateTime end,
                              int start, int limit) {
        var query = baseQuery(method, keyword, begin, end).orderByDesc(GbOperationLog::getId);
        if (limit > 0) {
            query.last("limit " + limit + " offset " + Math.max(start, 0));
        }
        return logMapper.selectList(query).stream().map(OperationLogService::toVo).toList();
    }

    /** 查询条件组装（list 与 count 共用）。 */
    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GbOperationLog> baseQuery(
            String method, String keyword, LocalDateTime begin, LocalDateTime end) {
        var query = Wrappers.<GbOperationLog>lambdaQuery();
        if (method != null && !method.isBlank() && !"all".equalsIgnoreCase(method)) {
            query.eq(GbOperationLog::getMethod, method);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(GbOperationLog::getResource, keyword)
                    .or().like(GbOperationLog::getRemoteAddr, keyword)
                    .or().like(GbOperationLog::getOperator, keyword));
        }
        if (begin != null) {
            query.ge(GbOperationLog::getCreateTime, begin);
        }
        if (end != null) {
            query.le(GbOperationLog::getCreateTime, end);
        }
        return query;
    }

    /** 符合条件的总条数。 */
    public long count(String method, String keyword, LocalDateTime begin, LocalDateTime end) {
        return logMapper.selectCount(baseQuery(method, keyword, begin, end));
    }

    /** 清空全部操作日志（带 id > 0 条件，规避全表删除拦截）。 */
    public int clear() {
        return logMapper.delete(Wrappers.<GbOperationLog>lambdaQuery().gt(GbOperationLog::getId, 0));
    }

    /**
     * 清理过期日志（默认保留 7 天）。
     *
     * <p>由 {@code PlatformScheduler} 定期调用；保留天数可用
     * {@code gb28181.starter.oplog-retain-days} 配置。
     */
    public int purgeExpired(int retainDays) {
        LocalDateTime deadline = LocalDateTime.now().minusDays(Math.max(retainDays, 1));
        return logMapper.delete(Wrappers.<GbOperationLog>lambdaQuery()
                .lt(GbOperationLog::getCreateTime, deadline));
    }

    /** 实体 → VO。 */
    public static OpLogVo toVo(GbOperationLog e) {
        OpLogVo vo = new OpLogVo();
        vo.setId(e.getId());
        vo.setMethod(e.getMethod());
        vo.setOperationName(operationNameOf(e.getResource()));
        vo.setResource(e.getResource());
        vo.setRemoteAddr(e.getRemoteAddr());
        vo.setResult(e.getResult());
        vo.setDurationMs(e.getDurationMs());
        vo.setOperator(e.getOperator());
        // 请求参数：审计的核心信息（改了什么），不返回出去等于没记
        vo.setParams(e.getParams());
        vo.setCreateTime(e.getCreateTime() == null ? null : e.getCreateTime().toString().replace('T', ' '));
        return vo;
    }
}
