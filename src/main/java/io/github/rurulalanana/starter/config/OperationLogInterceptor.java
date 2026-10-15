package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 操作日志拦截器：记录 {@code /api/v1/**} 的接口调用。
 *
 * <p>参考 LiveGBS 的「操作日志」页：记录调用方式、资源路径、远端地址、
 * 结果（HTTP 状态码 / 异常名）与耗时。写入由 {@link OperationLogService} 完成，
 * 失败不影响请求本身。
 *
 * <p><b>排除项</b>：高频轮询与日志自身接口（避免日志被刷屏、避免自噬）：
 * <ul>
 *   <li>{@code /api/v1/oplog/**}：日志查询/清空接口本身</li>
 *   <li>{@code /api/v1/device/onlinestats}：总览页 10 秒自动刷新</li>
 *   <li>{@code /api/v1/stream/info}：播放页状态轮询</li>
 *   <li>{@code /api/v1/webrtc/**}：播放协商（轮播场景下高频）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogInterceptor implements HandlerInterceptor {

    /** 请求开始时间戳的 attribute 名。 */
    private static final String START_ATTR = "gb211.oplog.start";

    /** 不记录的路径前缀。 */
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/api/v1/oplog",
            "/api/v1/webrtc"
    );

    /** 不记录的具体路径（精确匹配）。 */
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/v1/device/onlinestats",
            "/api/v1/stream/info"
    );

    private final OperationLogService operationLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            if (!shouldRecord(request)) {
                return;
            }
            Object start = request.getAttribute(START_ATTR);
            long duration = start instanceof Long s ? System.currentTimeMillis() - s : 0L;

            String method = "HTTP_" + request.getMethod();
            String resource = request.getRequestURI();
            String remoteAddr = clientIp(request);
            String result = ex == null
                    ? String.valueOf(response.getStatus())
                    : ex.getClass().getSimpleName();

            operationLogService.record(method, resource, remoteAddr, result, duration);
        } catch (Exception e) {
            // 日志失败绝不影响请求
            log.debug("操作日志记录异常：{}", e.toString());
        }
    }

    /** 是否记录该请求。 */
    private boolean shouldRecord(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if (EXCLUDED_PATHS.contains(path)) {
            return false;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** 取客户端 IP（兼容反向代理的 X-Forwarded-For）。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
