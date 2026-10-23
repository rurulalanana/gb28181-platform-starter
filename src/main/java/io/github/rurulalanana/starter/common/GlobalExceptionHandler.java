package io.github.rurulalanana.starter.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常统一收敛成 {@link ApiResponse}。
 *
 * <p>不改变 HTTP 状态码（一律 200），与 GBS 的约定一致：
 * 客户端只需看 {@code code} 字段。
 *
 * <p><b>日志分级的原则</b>：只有「服务端真出了问题」才配 ERROR + 堆栈。
 * 调用方错误（参数不合法、设备离线、资源不存在）和客户端行为
 * （主动断开连接）都是<b>预期内的日常</b>，给 WARN/DEBUG 一行就够。
 * 之前不分级的结果是 1600 多条 ERROR 里 95% 是噪音，
 * 真故障要靠关键字过滤才看得见 —— 等于白记。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 信令库在设备未注册 / 会话失效时抛出的异常消息前缀。
     *
     * <p>与 {@code Gb28181SipClient#requireSession} 保持一致。
     */
    private static final String MSG_DEVICE_NOT_REGISTERED = "设备未注册或会话已失效";

    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(ApiResponse.CODE_UNAUTHORIZED, "未登录或登录已失效");
    }

    /**
     * 角色不足（403）。
     *
     * <p><b>必须单独处理</b>：{@code StpUtil.checkRoleOr("admin")} 在角色不足时
     * 抛的是 {@code NotRoleException}，它与 {@code NotPermissionException}
     * 是<b>兄弟类</b>而非父子关系，只声明后者会落到兜底的 {@code handleOther}，
     * 返回 {@code code=500}。前端据此弹「服务端异常」，而实际语义是
     * 「你没权限」——两者对使用者的引导完全不同（前者让找运维，后者让找管理员）。
     */
    @ExceptionHandler(NotRoleException.class)
    public ApiResponse<Void> handleNotRole(NotRoleException e) {
        return ApiResponse.fail(ApiResponse.CODE_FORBIDDEN, "无权限访问：需要管理员角色");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ApiResponse<Void> handleNotPermission(NotPermissionException e) {
        return ApiResponse.fail(ApiResponse.CODE_FORBIDDEN, "无权限访问：" + e.getMessage());
    }

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数类异常统一收敛为 400。
     *
     * <p><b>为什么要显式列出 {@code MethodArgumentTypeMismatchException} /
     * {@code BindException} / {@code MethodArgumentNotValidException}</b>：
     * 它们都继承自 {@code Exception} 而非 {@code IllegalArgumentException}，
     * 之前只声明了后者，于是 {@code ?limit=abc}、{@code ?begin=notatime}
     * 这类类型转换失败会落到兜底的 {@code handleOther}，返回 {@code code=500}，
     * 前端弹出「服务端异常」而不是「参数错误」。这类输入属于调用方错误，
     * 必须回 400，否则监控上会把正常探测流量误报成服务故障。
     *
     * <p>另外这些异常的 {@code getMessage()} 是 Spring 的内部描述
     * （如 {@code Failed to convert value of type 'java.lang.String' to required type 'int'}），
     * 对使用者毫无意义。这里统一提取最内层原因，并去掉冗长的类名与参数签名。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            BindException.class,
            MethodArgumentNotValidException.class,
            TypeMismatchException.class})
    public ApiResponse<Void> handleBadRequest(Exception e) {
        return ApiResponse.fail(ApiResponse.CODE_BAD_REQUEST, "参数错误：" + friendlyMessage(e));
    }

    /**
     * 把 Spring 的参数异常描述转成可读文案。
     *
     * <p>例如把
     * {@code Failed to convert value of type 'java.lang.String' to required type 'int'; nested exception is ...NumberFormatException: For input string: "abc"}
     * 收敛成 {@code 参数 limit 的值 "abc" 类型不正确}。
     */
    private String friendlyMessage(Exception e) {
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return "参数 " + mismatch.getName() + " 的值 \"" + mismatch.getValue() + "\" 类型不正确";
        }
        if (e instanceof MissingServletRequestParameterException missing) {
            return "缺少必填参数 " + missing.getParameterName();
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式不正确（应为合法 JSON）";
        }
        if (e instanceof BindException bind && bind.getFieldError() != null) {
            var fe = bind.getFieldError();
            return "参数 " + fe.getField() + " 的值 \"" + fe.getRejectedValue() + "\" 类型不正确";
        }
        if (e instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getFieldError() != null) {
            var fe = invalid.getBindingResult().getFieldError();
            return "参数 " + fe.getField() + " 不合法：" + fe.getDefaultMessage();
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        // 去掉 Spring 拼在消息后面的类名/方法签名，只留一句人话
        int cut = msg.indexOf(" in public ");
        if (cut > 0) {
            msg = msg.substring(0, cut);
        }
        return msg;
    }

    /**
     * 静态资源不存在（404）。
     *
     * <p><b>为什么要单独处理</b>：浏览器每次打开页面都会自动请求
     * {@code /favicon.ico}，源码扫描器 / 渗透扫描还会乱拉各种路径。
     * 这些请求没有对应的 Controller，Spring 6 会抛
     * {@code NoResourceFoundException}，之前落到兜底的 {@code handleOther}
     * 里打一条 ERROR + 完整堆栈（二十多行），结果每次刷新页面日志就多一屏噪音，
     * 真正的故障被埋在中间。
     *
     * <p>现在降级为 WARN（只记一行、不带堆栈），返回 404 语义的 code，
     * 不再影响日志可读性。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        String path = e.getResourcePath();
        // sourcemap 请求降级为 debug：浏览器 DevTools 打开时会自动拉
        // xxx.css.map / xxx.js.map，而构建产物默认不生成 sourcemap。
        // 这不是故障，也不该占用 WARN —— 每次开 DevTools 就刷两条日志，
        // 真正的问题反而被埋掉。
        if (path != null && path.endsWith(".map")) {
            log.debug("sourcemap 不存在（构建未生成，正常）: {}", path);
        } else {
            log.warn("静态资源不存在：{}", path);
        }
        return ApiResponse.fail(404, "资源不存在：" + path);
    }

    /**
     * 客户端主动断开连接。
     *
     * <p><b>这是正常现象，不是故障</b>：用户切页面、暂停/停止播放、
     * 拖动进度条取消上一段 Range 下载，都会让容器在写响应时发现对端已关闭，
     * 抛 {@code ClientAbortException}（Tomcat）或
     * {@code AsyncRequestNotUsableException}（Spring 6 的异步请求）。
     *
     * <p>之前它们落到兜底的 {@code handleOther}，每条都打 ERROR + 二十多行堆栈。
     * 实测攒了 <b>830 条</b>（ClientAbort 741 + AsyncNotUsable 89），
     * 真正的故障全被埋在中间 —— 排查时只能靠关键字过滤，等于没有日志。
     *
     * <p>这里降级为 DEBUG（连 WARN 都不给：切页是高频动作，WARN 一样会刷屏），
     * 并且<b>不返回响应体</b> —— 对端已经关了，写什么都没人收。
     */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    public void handleClientAbort(Exception e) {
        log.debug("客户端已断开连接：{}", e.getClass().getSimpleName());
    }

    /**
     * 设备未注册 / 会话已失效。
     *
     * <p>信令库（{@code gb28181-sip}）零 Spring 依赖，无法抛 {@code BizException}，
     * 只能抛 {@code IllegalStateException}，于是这类<b>最常见的正常情况</b>
     * 也走了兜底的 ERROR + 完整堆栈：现场 7 台设备常年只有 1 台在线，
     * 用户点一下离线设备就多一条假故障（实测 203 条）。
     *
     * <p>按<b>消息前缀</b>识别并转成 400 业务错误。前缀常量与
     * {@code Gb28181SipClient#requireSession} 保持一致 —— 那边改文案的话
     * 这里要同步；不同步的后果只是退回成打 ERROR，不影响功能。
     *
     * <p>其余 {@code IllegalStateException} 仍按服务端故障处理（打堆栈），
     * 免得把真正的状态机 bug 一起吞掉。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> handleIllegalState(IllegalStateException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith(MSG_DEVICE_NOT_REGISTERED)) {
            log.warn("设备未注册或会话已失效：{}", msg);
            return ApiResponse.fail(ApiResponse.CODE_BAD_REQUEST, msg);
        }
        log.error("接口异常", e);
        return ApiResponse.fail(ApiResponse.CODE_ERROR, msg.isEmpty() ? e.getClass().getSimpleName() : msg);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("接口异常", e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ApiResponse.fail(ApiResponse.CODE_ERROR, msg);
    }
}
