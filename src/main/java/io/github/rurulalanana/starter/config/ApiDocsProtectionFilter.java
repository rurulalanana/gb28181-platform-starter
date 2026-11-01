package io.github.rurulalanana.starter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 接口文档的 HTTP Basic 保护（{@code gb28181.api-docs.protect=true} 时生效）。
 *
 * <h3>为什么不用 Knife4j 自带的 basic</h3>
 *
 * <p>Knife4j 的 basic 在它的 starter 里，而那个 starter 与 springdoc 2.6 字节码不兼容
 * （见 {@code pom.xml} 里的说明）。这里用 30 行自己实现，反而少一个会互相打架的依赖。
 *
 * <h3>为什么保护文档而不是关掉它</h3>
 *
 * <p>接口清单会暴露全部路径、参数与出参结构，公网裸奔等于给扫描器送地图。
 * 但完全关掉又会让对接方没法自查（{@code /v3/api-docs} 是给 Postman/Apifox 导入用的）。
 * Basic 是个折中：浏览器会弹原生登录框，凭据只存在当次会话，不需要账号体系。
 *
 * <p><b>注意</b>：这里的 Basic 凭据与平台账号（Sa-Token）无关，是一次性的文档口令；
 * 真正的数据仍要登录 token 才能取。
 *
 * <h3>为什么比的是常量时间</h3>
 *
 * <p>用户名口令比对用 {@link MessageDigest#isEqual} 而不是 {@code equals}，
 * 避免通过响应时间差逐字节猜口令。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gb28181.api-docs.protect", havingValue = "true")
public class ApiDocsProtectionFilter extends OncePerRequestFilter {

    /** 需要保护的路径前缀：Knife4j 界面、OpenAPI JSON、Swagger UI 与它的 webjar 资源。 */
    private static final String[] PROTECTED_PREFIXES = {
            "/doc.html", "/v3/api-docs", "/swagger-ui", "/webjars"
    };

    private final String username;
    private final String password;

    public ApiDocsProtectionFilter(
            @Value("${gb28181.api-docs.basic-username:gb28181}") String username,
            @Value("${gb28181.api-docs.basic-password:}") String password) {
        if (password == null || password.isBlank()) {
            // 开着保护却不给口令 = 任何人都能用空口令进文档；
            // 与其静默放行，不如在启动时就断开，逼部署方补上 GB_DOCS_PASSWORD
            throw new IllegalStateException(
                    "已开启 gb28181.api-docs.protect，但 gb28181.api-docs.basic-password 为空："
                            + "请通过环境变量 GB_DOCS_PASSWORD 指定文档口令");
        }
        this.username = username;
        this.password = password;
        log.info("接口文档已开启 HTTP Basic 保护（用户名 {}），访问 /doc.html 需输入口令", username);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        // 401 + WWW-Authenticate：浏览器据此弹出原生登录框，并在后续子资源请求里自动带上
        response.setHeader("WWW-Authenticate", "Basic realm=\"GB28181 API Docs\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"接口文档需要口令（HTTP Basic）\"}");
    }

    private boolean isProtected(String path) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 {@code Authorization: Basic base64(user:pass)}。
     *
     * <p>只认 {@code Basic} 方案：Sa-Token 的 token 也走 {@code Authorization} 头，
     * 但它是裸 token，不带方案前缀，这里自然不会误判。
     */
    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int sep = decoded.indexOf(':');
        if (sep < 0) {
            return false;
        }
        return constantEquals(username, decoded.substring(0, sep))
                && constantEquals(password, decoded.substring(sep + 1));
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
