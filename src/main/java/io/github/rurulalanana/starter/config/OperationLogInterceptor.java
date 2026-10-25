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
    /** 请求参数（已脱敏）的 attribute 名。 */
    private static final String PARAMS_ATTR = "gb211.oplog.params";
    /**
     * 业务结果码/描述的 attribute 名。
     *
     * <p>由 {@code ApiResponseAdvice} 写入。用请求属性传递而不直接读响应体，
     * 是因为响应体可能已经被序列化写入输出流，事后读取会二次消耗流。
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
            // 每次都记一条操作日志会把日志表刷满，且这些行没有任何审计价值
            // （用户真正看的操作是「播放/下载」，不是「第 37 个分段请求」）。
            "/api/v1/record/auth"
    );

    private final OperationLogService operationLogService;
    private final UserService userService;

    /** 登录 ID → 用户名缓存（日志落库用用户名，避免每条日志查库）。 */
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        /*
         * 参数在请求流水线里就先抓下来。
         *
         * <p>为什么不在 afterCompletion 里读：{@code getReader()/getInputStream()}
         * 只能读一次，控制器读完后再读会抛 IllegalStateException；
         * 而 query string 虽然随时可读，但 POST 的主体才是真正的改数据内容。
         * 所以这里用 {@code getParameterMap()}（Spring 已解析好表单），
         * 并对 JSON body 采用「由 ResponseBodyAdvice 之外的地方不着手」的策略 ——
         * 见下方说明。
         */
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

            /*
             * 结果列必须是「业务结果」而不是 HTTP 状态码。
             *
             * <p>本项目为了让前端统一处理，约定 HTTP 状态码恒为 200、真实结果放在
             * body 的 {@code code} 里。于是原来的 {@code response.getStatus()}
             * 永远写入 "200" —— 一次被拒绝的越权删除、一次参数校验失败、
             * 一次服务端异常，在审计日志里全都显示为成功。
             * 这样的日志在追责时毫无价值，甚至会把排查方向带偏
             * （「日志显示成功，那问题应该在别处」）。
             *
             * <p>现在改为优先取业务码（由 {@code ApiResponseAdvice} 写入请求属性），
             * 其次才是 HTTP 状态，最后是异常类型。
             */
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
     * 采集请求参数并脱敏。
     *
     * <p>覆盖两种最常见的传参形态：
     * <ul>
     *   <li>query string（GET 与部分 POST）；</li>
     *   <li>表单参数（{@code getParameterMap()} 合并了 query 与 form body）。</li>
     * </ul>
     * JSON body 不在这里读（流只能读一次，读了控制器就拿不到），
     * 这类接口（如证书上传、级联保存）由控制器自己写关键字段的审计，
     * 或退化为「只有 URL」—— 好过为了记日志把请求体吃掉。
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
     * 敏感字段脱敏。
     *
     * <p><b>审计日志本身不能变成凭据泄漏源</b>：它会被导出、被备份、被开发同学拷到本地排查。
     * 一旦里面躺着设备接入密码、私钥、登录 token，等于把整站的钥匙散到每个接触日志的人手里。
     * 宁可丢掉诊断细节，也不记明文。
     *
     * <p><b>但也不能过度脱敏</b> —— 这是一个实测踩到的坑：最初用
     * {@code name.contains("key")} 一刀切，结果 {@code /config/save} 的参数名
     * 恰好就叫 {@code key}（配置项名称），被替换成 {@code ***(12字符)}，
     * 审计于是丢掉了「改的是<b>哪一项</b>配置」这个最关键的信息 ——
     * 而那正是这轮改审计要解决的问题本身。
     *
     * <p>所以这里的判据是「<b>像凭据的字段名</b>」而不是「含敏感词」：
     * 裸的 {@code key} / {@code id} / {@code code} 不脱敏（它们通常是标识符），
     * {@code accessKey} / {@code privateKey} / {@code keyPem} 这类才脱敏。
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
        /*
         * cert：证书内容本身是公开信息，但「上传证书」的请求体可能同时带私钥，
         * 而这类接口（TlsController）走 JSON body，本方法拿不到 —— 这里保留
         * 对 cert 的脱敏作为额外保险：宁可看不到证书内容，也别哪天有人把
         * 私钥塞进名叫 cert 的表单字段。
         */
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
