package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把业务结果码挂到请求属性上，供审计日志使用。
 *
 * <p><b>为什么需要这一层</b>：本项目约定「HTTP 状态码恒为 200、真实结果放在
 * body 的 {@code code} 里」。于是审计日志若取 {@code response.getStatus()}，
 * 记录下来的永远是 200 —— 一次被拒绝的越权操作、一次参数校验失败、
 * 一次服务端异常，在日志里全都显示成功。这样的审计在追责时不但没用，
 * 还会把排查方向带偏。
 *
 * <p>这里在响应体写出前把 {@code code / msg} 暂存到请求属性，
 * 由 {@link OperationLogInterceptor#afterCompletion} 读取。
 * 用属性传递而不是事后解析响应体：响应流已经写出，再读会二次消耗。
 *
 * <p><b>对本类的要求是「绝对不能影响响应」</b>：它挂在每个接口的返回路径上，
 * 任何异常都会变成接口 500。所以内部全部 try/catch，失败时什么都不做。
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        try {
            if (body instanceof ApiResponse<?> api && request instanceof ServletServerHttpRequest servlet) {
                HttpServletRequest req = servlet.getServletRequest();
                req.setAttribute(OperationLogInterceptor.BIZ_CODE_ATTR, api.getCode());
                req.setAttribute(OperationLogInterceptor.BIZ_MSG_ATTR, api.getMsg());
            }
        } catch (Exception ignored) {
            // 审计信息采集失败不能影响业务响应
        }
        return body;
    }
}
