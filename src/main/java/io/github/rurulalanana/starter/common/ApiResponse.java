package io.github.rurulalanana.starter.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>固定为 {@code { code, msg, data }} 三段式，同时兼容「直接返回业务对象」
 * 的接口形态：{@link #of(Object)} 把业务对象包进 {@code data}。
 *
 * <p>{@code code} 约定：
 * <ul>
 *   <li>{@code 0} 成功</li>
 *   <li>{@code 401} 未登录 / Token 失效</li>
 *   <li>{@code 403} 无权限</li>
 *   <li>{@code 400} 参数错误</li>
 *   <li>{@code 500} 服务端异常</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CODE_OK = 0;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_ERROR = 500;

    private int code;

    private String msg;

    private T data;

    public static <T> ApiResponse<T> ok() {
        return of(null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return of(data);
    }

    public static <T> ApiResponse<T> of(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = CODE_OK;
        r.msg = "ok";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return fail(CODE_ERROR, msg);
    }
}
