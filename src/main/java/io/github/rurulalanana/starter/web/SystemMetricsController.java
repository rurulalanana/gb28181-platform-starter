package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.service.SystemMetricsService;
import io.github.rurulalanana.starter.vo.SystemMetricsVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统运行指标接口（概览页）。
 *
 * <pre>
 * GET /api/v1/system/metrics   主机指标 + 负载计数（前端每 3 秒轮询）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemMetricsController {

    private final SystemMetricsService metricsService;

    /** 系统运行指标。 */
    @GetMapping("/metrics")
    public ApiResponse<SystemMetricsVo> metrics() {
        StpUtil.checkLogin();
        return ApiResponse.ok(metricsService.metrics());
    }
}
