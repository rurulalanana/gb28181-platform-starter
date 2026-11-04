package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.service.OperationLogService;
import io.github.rurulalanana.starter.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志拦截器：记录 {@code /api/v1/**} 的调用方式、资源路径、远端地址、业务结果与耗时，
 * 写入失败不影响请求。排除高频轮询与日志自身接口：{@code /oplog/**}、10 秒刷新的
 * {@code device/onlinestats}、播放页轮询的 {@code stream/info}、轮播高频的 {@code webrtc/**}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "gb211.oplog.start";
    private static final String PARAMS_ATTR = "gb211.oplog.params";
    /**
     * 业务结果码/描述的 attribute 名，由 {@code ApiResponseAdvice} 写入。
     * 用请求属性而非直接读响应体：响应体可能已序列化进输出流，事后读会二次消耗流。
     */
    public static final String BIZ_CODE_ATTR = "gb211.oplog.bizCode";
    public static final String BIZ_MSG_ATTR = "gb211.oplog.bizMsg";

    /** 不记录的路径前缀。 */
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/api/v1/oplog",
            "/api/v1/webrtc"
    );

    /** 不记录的具体路径（精确匹配）。 */
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/v1/device/onlinestats",
            "/api/v1/system/metrics",
            "/api/v1/stream/info",
            // nginx 的 auth_request 子请求：播放一段录像要按 GOP 发几十次 Range，
            // 每次记一条会刷满日志表，且「第 37 个分段请求」没有审计价值
            "/api/v1/record/auth"
    );

    private final OperationLogService operationLogService;
    private final UserService userService;

    /** 登录 ID → 用户名缓存（日志落库用用户名，避免每条日志查库）。 */
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        // 参数必须在流水线里先抓：getReader()/getInputStream() 只能读一次，
        // 控制器读完后再读会抛 IllegalStateException。这里用 getParameterMap()（Spring 已解析表单），
        // JSON body 不碰，详见 collectParams。
        try {
            request.setAttribute(PARAMS_ATTR, collectParams(request));
        } catch (Exception e) {
            log.debug("采集请求参数失败：{}", e.toString());
        }
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

            // 结果列必须是业务结果而不是 HTTP 状态码：本项目 HTTP 恒为 200、真实结果在 body.code，
            // 原先写 response.getStatus() 时，被拒绝的删除/参数错误/服务端异常在审计里全显示成功。
            // 优先级：业务码（ApiResponseAdvice 写入）> HTTP 状态 > 异常类型。
            Object bizCode = request.getAttribute(BIZ_CODE_ATTR);
            Object bizMsg = request.getAttribute(BIZ_MSG_ATTR);
            String result;
            if (ex != null) {
                result = ex.getClass().getSimpleName();
                Throwable cause = ex.getCause();
                if (cause != null) {
                    result += ":" + cause.getClass().getSimpleName();
                }
            } else if (bizCode != null) {
                result = String.valueOf(bizCode);
                if (bizMsg != null && !"ok".equalsIgnoreCase(String.valueOf(bizMsg))) {
                    // 带上失败原因，否则只知道「失败了」不知道「为什么」
                    result += " " + String.valueOf(bizMsg);
                }
            } else {
                result = String.valueOf(response.getStatus());
            }
            if (result.length() > 128) {
                result = result.substring(0, 125) + "...";
            }

            Object params = request.getAttribute(PARAMS_ATTR);
            String paramsText = params == null ? null : String.valueOf(params);
            if (paramsText != null && paramsText.length() > 1024) {
                paramsText = paramsText.substring(0, 1021) + "...";
            }

            operationLogService.record(method, resource, remoteAddr, result, duration,
                    operator(), paramsText);
        } catch (Exception e) {
            // 日志失败绝不影响请求
            log.debug("操作日志记录异常：{}", e.toString());
        }
    }

    /**
     * 采集请求参数并脱敏。覆盖 query string 与表单参数（getParameterMap 合并两者）；
     * JSON body 不在这里读（流只能读一次），这类接口由控制器自己写关键字段的审计，
     * 或退化为「只有 URL」——好过为了记日志把请求体吃掉。
     */
    private String collectParams(HttpServletRequest request) {
        Map<String, String[]> map = request.getParameterMap();
        if (map == null || map.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String name = e.getKey();
            String[] values = e.getValue();
            String value = values == null || values.length == 0 ? ""
                    : String.join(",", values);
            sb.append(name).append('=').append(mask(name, value));
            if (sb.length() > 1024) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 敏感字段脱敏。审计日志会被导出、备份、拷到本地排查，不能变成凭据泄漏源：
     * 里面躺着设备密码、私钥、登录 token 等于把钥匙散给每个接触日志的人。
     * 但也不能过度脱敏：最初用 {@code name.contains("key")} 一刀切，{@code /config/save}
     * 的参数名恰好叫 {@code key}（配置项名称），被替换成 {@code ***(12字符)}，
     * 丢掉了「改的是哪一项配置」这个最关键的信息。
     * 故判据是「像凭据的字段名」而非「含敏感词」：裸 {@code key/id/code} 不脱敏。
     */
    private static String mask(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (isSensitiveName(name)) {
            return "***(" + value.length() + "字符)";
        }
        return value;
    }

    /** 字段名是否像凭据。 */
    private static boolean isSensitiveName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        // 明确的凭据类名词
        if (n.contains("password") || n.contains("passwd") || n.contains("pwd")) {
            return true;
        }
        if (n.contains("secret") || n.contains("token") || n.contains("credential")) {
            return true;
        }
        // 私钥 / 证书内容（PEM 长度通常在数百字符以上，一眼可辨）
        if (n.contains("privatekey") || n.contains("keypem") || n.contains("keystore")) {
            return true;
        }
        // 各类云厂商的访问密钥
        if (n.contains("accesskey") || n.contains("secretkey") || n.contains("apikey")) {
            return true;
        }
        // cert：证书本身是公开信息，但上传接口可能同时带私钥，且这类请求体走 JSON 拿不到，
        // 保留对 cert 的脱敏作为额外保险。
        if (n.contains("cert")) {
            return true;
        }
        // 裸的 key / value / id / code / serial 等是标识符，不脱敏
        return false;
    }

    /** 取操作人（用户名；未登录接口如 /login 返回 null）。 */
    private String operator() {
        try {
            long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
            return userNames.computeIfAbsent(userId, id -> {
                try {
                    GbUser user = userService.findById(id);
                    return user != null ? user.getUsername() : String.valueOf(id);
                } catch (Exception e) {
                    return String.valueOf(id);
                }
            });
        } catch (Exception e) {
            return null;
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
