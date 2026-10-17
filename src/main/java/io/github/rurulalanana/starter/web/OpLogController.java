package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.service.OperationLogService;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.OpLogListVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志接口（对齐 LiveGBS 的「操作日志」页）。
 *
 * <pre>
 * GET  /api/v1/oplog/list    分页查询（method 筛选 + keyword 关键字）
 * POST /api/v1/oplog/clear   清空全部
 * </pre>
 *
 * <p>日志由 {@code OperationLogInterceptor} 自动记录，本控制器只负责查询与清空。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oplog")
@RequiredArgsConstructor
public class OpLogController {

    private final OperationLogService operationLogService;

    /** 分页查询。begin/end 为可选时间范围（yyyy-MM-dd HH:mm:ss）。 */
    @GetMapping("/list")
    public ApiResponse<OpLogListVo> list(@RequestParam(defaultValue = "0") int start,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(required = false) String method,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false)
                                         @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                         java.time.LocalDateTime begin,
                                         @RequestParam(required = false)
                                         @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                         java.time.LocalDateTime end) {
        StpUtil.checkLogin();
        OpLogListVo vo = new OpLogListVo();
        vo.setOpLogCount(operationLogService.count(method, keyword, begin, end));
        vo.setOpLogList(operationLogService.list(method, keyword, begin, end, start, limit));
        return ApiResponse.ok(vo);
    }

    /**
     * 清空全部日志。
     *
     * <p>仅管理员可执行：审计日志是事后追责的唯一依据，
     * 普通用户若能清空，等于任何账号都能抹掉自己的操作痕迹。
     */
    @PostMapping("/clear")
    public ApiResponse<DeletedVo> clear() {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        int deleted = operationLogService.clear();
        log.info("操作日志已清空：{} 条（操作人 {}）", deleted, StpUtil.getLoginId());
        DeletedVo vo = new DeletedVo();
        vo.setDeleted(deleted);
        return ApiResponse.ok(vo);
    }
}
