package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.service.RecordUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 录像地址鉴权端点——<b>供 nginx 的 {@code auth_request} 子请求调用</b>。
 *
 * <p><b>它故意不遵守 starter 的接口约定</b>：
 * <ul>
 *   <li>不包 {@code ApiResponse}、不返回 JSON —— nginx 只看 HTTP 状态码；</li>
 *   <li>状态码<b>不</b>恒为 200 —— {@code 2xx} 放行、{@code 403} 拒绝，
 *       这正是子请求协议的语义。</li>
 * </ul>
 * 若按项目约定包成「HTTP 200 + body 里写错误码」，nginx 会把所有被拒绝的
 * 请求都当成放行，<b>鉴权形同虚设且没有任何报错</b>。这是唯一的例外，
 * 不要照抄到别处。
 *
 * <pre>
 * nginx 侧配置（deploy/nginx-record.conf）：
 *
 *   location /record/ {
 *       auth_request /_record_auth;
 *       ...
 *   }
 *   location = /_record_auth {
 *       internal;
 *       proxy_pass http://127.0.0.1:48092/api/v1/record/auth;
 *       proxy_pass_request_body off;
 *       proxy_set_header Content-Length "";
 *       proxy_set_header X-Original-URI $request_uri;
 *   }
 * </pre>
 *
 * <p>为什么要转发 {@code X-Original-URI}：子请求自身的 URI 恒为
 * {@code /_record_auth}，不含待校验的录像路径与签名。不转发就等于在验一个空串。
 *
 * <p>本端点不参与登录校验（nginx 无法携带 Sa-Token 头），安全性完全由
 * 地址里的签名保证；签名关闭时它一律放行，与 {@link RecordUrlSigner#allow} 一致。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RecordAuthController {

    private final RecordUrlSigner signer;

    /**
     * 校验录像请求。
     *
     * @param originalUri nginx 转发的原始请求 URI（含 query）
     */
    @GetMapping("/api/v1/record/auth")
    public void auth(@RequestHeader(value = "X-Original-URI", required = false) String originalUri,
                     HttpServletRequest request,
                     HttpServletResponse response) {
        // 没有 nginx 转发头时（例如直接 curl 本端点）用请求自身兜底，
        // 这样排查时至少能用 /api/v1/record/auth?e=..&t=.. 自测
        String uri = originalUri != null ? originalUri
                : request.getRequestURI() + (request.getQueryString() == null
                        ? "" : "?" + request.getQueryString());
        if (signer.allow(uri)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        log.debug("录像地址鉴权拒绝: {}", uri);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
