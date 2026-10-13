package io.github.rurulalanana.starter.common;

import lombok.Getter;

/**
 * 业务异常：由 {@link GlobalExceptionHandler} 统一转成 {@link ApiResponse}。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        this(ApiResponse.CODE_ERROR, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 参数错误（400）。 */
    public static BizException badRequest(String message) {
        return new BizException(ApiResponse.CODE_BAD_REQUEST, message);
    }

    /** 未找到（400，沿用 GBS 风格把「不存在」也算参数问题）。 */
    public static BizException notFound(String message) {
        return new BizException(ApiResponse.CODE_BAD_REQUEST, message);
    }
}
