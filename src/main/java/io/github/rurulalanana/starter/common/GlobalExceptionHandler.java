package io.github.rurulalanana.starter.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把各类异常统一收敛成 {@link ApiResponse}。
 *
 * <p>不改变 HTTP 状态码（一律 200），与 GBS 的约定一致：
 * 客户端只需看 {@code code} 字段。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        return ApiResponse.fail(ApiResponse.CODE_UNAUTHORIZED, "未登录或登录已失效");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ApiResponse<Void> handleNotPermission(NotPermissionException e) {
        return ApiResponse.fail(ApiResponse.CODE_FORBIDDEN, "无权限访问：" + e.getMessage());
    }

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ApiResponse<Void> handleBadRequest(Exception e) {
        return ApiResponse.fail(ApiResponse.CODE_BAD_REQUEST, "参数错误：" + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("接口异常", e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ApiResponse.fail(ApiResponse.CODE_ERROR, msg);
    }
}
