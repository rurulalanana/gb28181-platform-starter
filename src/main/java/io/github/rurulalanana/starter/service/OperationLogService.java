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

/**
 * 操作日志服务：记录管理接口调用、分页查询、清空与过期清理。
 *
 * <p>参考 LiveGBS 的「操作日志」：记录调用方式（HTTP GET/POST）、资源路径、
 * 远端地址、结果与用时，便于排查「谁在什么时候调了什么」。
 *
 * <p>写入失败绝不向上抛（日志是旁路功能，不能影响正常请求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final GbOperationLogMapper logMapper;

    /** 记录一条操作日志（同步写，失败仅记 warn）。 */
    public void record(String method, String resource, String remoteAddr, String result, long durationMs) {
        try {
            GbOperationLog entry = new GbOperationLog();
            entry.setMethod(method);
            entry.setResource(resource);
            entry.setRemoteAddr(remoteAddr);
            entry.setResult(result);
            entry.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            entry.setCreateTime(LocalDateTime.now());
            logMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写入操作日志失败：{} {} -> {}", method, resource, e.toString());
        }
    }

    /** 分页查询（游标语义：start 偏移 + limit 页大小，limit=0 不分页）。 */
    public List<OpLogVo> list(String method, String keyword, int start, int limit) {
        var query = Wrappers.<GbOperationLog>lambdaQuery()
                .orderByDesc(GbOperationLog::getId);
        if (method != null && !method.isBlank() && !"all".equalsIgnoreCase(method)) {
            query.eq(GbOperationLog::getMethod, method);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(GbOperationLog::getResource, keyword)
                    .or().like(GbOperationLog::getRemoteAddr, keyword));
        }
        if (limit > 0) {
            query.last("limit " + limit + " offset " + Math.max(start, 0));
        }
        return logMapper.selectList(query).stream().map(OperationLogService::toVo).toList();
    }

    /** 符合条件的总条数。 */
    public long count(String method, String keyword) {
        var query = Wrappers.<GbOperationLog>lambdaQuery();
        if (method != null && !method.isBlank() && !"all".equalsIgnoreCase(method)) {
            query.eq(GbOperationLog::getMethod, method);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(GbOperationLog::getResource, keyword)
                    .or().like(GbOperationLog::getRemoteAddr, keyword));
        }
        return logMapper.selectCount(query);
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
        vo.setResource(e.getResource());
        vo.setRemoteAddr(e.getRemoteAddr());
        vo.setResult(e.getResult());
        vo.setDurationMs(e.getDurationMs());
        vo.setCreateTime(e.getCreateTime() == null ? null : e.getCreateTime().toString().replace('T', ' '));
        return vo;
    }
}
