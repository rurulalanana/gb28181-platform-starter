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
 * 全局异常处理：把各类异常统一收敛成 {@link ApiResponse}。不改变 HTTP 状态码（一律 200），
 * 与 GBS 约定一致：客户端只看 {@code code} 字段。
 *
 * <p>日志分级原则：只有服务端真出问题才配 ERROR + 堆栈。调用方错误（参数不合法、设备离线、
 * 资源不存在）与客户端断开都是预期内的日常。不分级时 1600 多条 ERROR 里 95% 是噪音。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 信令库在设备未注册 / 会话失效时抛出的异常消息前缀，与 {@code Gb28181SipClient#requireSession} 一致。 */
    private static final String MSG_DEVICE_NOT_REGISTERED = "设备未注册或会话已失效";

    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(ApiResponse.CODE_UNAUTHORIZED, "未登录或登录已失效");
    }

    /**
     * 角色不足（403）。必须单独处理：{@code NotRoleException} 与 {@code NotPermissionException}
     * 是兄弟类而非父子关系，只声明后者会落到兜底 handleOther 返回 500，前端弹「服务端异常」
     * 而不是「你没权限」，引导方向完全不同。
     */
    @ExceptionHandler(NotRoleException.class)
    public ApiResponse<Void> handleNotRole(NotRoleException e) {
        log.warn("拒绝越权请求（角色断言）：role={}", e.getRole());
        return ApiResponse.fail(ApiResponse.CODE_FORBIDDEN, "没有「" + e.getRole() + "」角色，请联系管理员");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ApiResponse<Void> handleNotPermission(NotPermissionException e) {
        // 把权限点编码翻成中文名：Sa-Token 原文「无此权限：device:write」使用者看不懂，
        // 翻成「编辑设备」后报错本身就能指导他找管理员要哪个权限。
        String code = e.getPermission();
        Permissions.Def def = Permissions.defOf(code);
        String name = def == null ? code : def.name();
        log.warn("拒绝越权请求（接口内断言）：permission={}", code);
        return ApiResponse.fail(ApiResponse.CODE_FORBIDDEN, "没有「" + name + "」权限，请联系管理员调整角色");
    }

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数类异常统一收敛为 400。必须显式列出类型转换 / 绑定异常：它们继承自 Exception 而非
     * IllegalArgumentException，只声明后者时 {@code ?limit=abc}、{@code ?begin=notatime}
     * 会落到 handleOther 返回 500，前端弹「服务端异常」，监控也会把正常探测流量误报成故障。
     * 这些异常的 getMessage() 是 Spring 内部描述，故统一提取最内层原因并去掉类名/方法签名。
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

    /** 把 Spring 的参数异常描述收敛成一句人话（如参数 limit 的值 "abc" 类型不正确）。 */
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
     * 静态资源不存在（404）。必须单独处理：浏览器每次都请求 {@code /favicon.ico}，扫描器还会乱拉路径，
     * Spring 6 抛 {@code NoResourceFoundException} 落到 handleOther 会打 ERROR + 二十多行堆栈，
     * 每次刷新页面就多一屏噪音。这里降级为 WARN（一行、无堆栈）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        String path = e.getResourcePath();
        // sourcemap 降级为 debug：DevTools 打开时会自动拉 .map，而构建默认不生成，
        // 不是故障、也不该占 WARN（每次开 DevTools 刷两条日志）。
        if (path != null && path.endsWith(".map")) {
            log.debug("sourcemap 不存在（构建未生成，正常）: {}", path);
        } else {
            log.warn("静态资源不存在：{}", path);
        }
        return ApiResponse.fail(404, "资源不存在：" + path);
    }

    /**
     * 客户端主动断开连接。这是正常现象：切页面、暂停播放、拖动进度条取消上一段 Range 下载，
     * 都会让容器在写响应时发现对端已关闭，抛 {@code ClientAbortException}（Tomcat）或
     * {@code AsyncRequestNotUsableException}（Spring 6 异步请求）。
     * 之前落到 handleOther 每条打 ERROR + 二十多行堆栈，实测攒了 830 条（741 + 89）。
     * 这里降级为 DEBUG，且不返回响应体 —— 对端已关，写什么都没人收。
     */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    public void handleClientAbort(Exception e) {
        log.debug("客户端已断开连接：{}", e.getClass().getSimpleName());
    }

    /**
     * 设备未注册 / 会话已失效。信令库零 Spring 依赖，不能抛 {@code BizException}，只能抛
     * {@code IllegalStateException}，于是最常见的正常情况也走了兜底 ERROR + 堆栈（实测 203 条）。
     * 按消息前缀识别并转成 400；前缀与 {@code Gb28181SipClient#requireSession} 保持一致，
     * 不同步只会退回成打 ERROR，不影响功能。其余 IllegalStateException 仍按服务端故障处理。
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
