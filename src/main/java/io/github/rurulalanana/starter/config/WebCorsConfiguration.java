package io.github.rurulalanana.starter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域（CORS）配置，供前后端分离部署使用。
 *
 * <h3>为什么默认关闭</h3>
 * 默认 {@code allowed-origins} 为空 = <b>不下发任何跨域响应头</b>。
 * 本项目前端产物打包进 jar 由同一个端口提供，同源部署根本不需要 CORS；
 * 而一旦默认放开（尤其是 {@code "*"}），任何网站都能在用户浏览器里
 * 带着他的登录态调本平台接口 —— 这是典型的 CSRF 放大面。
 * 所以必须由部署方显式列出允许的来源。
 *
 * <h3>为什么不能 {@code "*"} + {@code allowCredentials=true}</h3>
 * 浏览器明确禁止这个组合（CORS 规范：带凭据的请求不允许通配源），
 * 实际表现是所有请求全部失败且控制台报
 * {@code The value of the 'Access-Control-Allow-Origin' header ... must not be '*'}。
 * 本类遇到 {@code "*"} 时会自动回退为「不带凭据」，并在启动日志里警告。
 *
 * <h3>Sa-Token 的 token 头</h3>
 * 前端把登录态放在 {@code Authorization} 头里（见 {@code sa-token.token-name}），
 * 跨域时浏览器会先发 {@code OPTIONS} 预检，必须把该头加进
 * {@code allowedHeaders}，否则预检失败、真实请求根本发不出去。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "gb28181.cors")
public class WebCorsConfiguration implements WebMvcConfigurer {

    /**
     * 允许的来源，逗号分隔（如 {@code http://192.168.1.10:8081}）。
     *
     * <p>留空 = 关闭跨域。支持通配子域写法 {@code https://*.example.com}。
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 预检结果缓存秒数（默认 1 小时，减少 OPTIONS 往返）。 */
    private long maxAge = 3600L;

    /** 是否允许携带凭据（Cookie / Authorization）。 */
    private boolean allowCredentials = true;

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 过滤空串：yaml 里写成 `allowed-origins:` 后面留空会得到一个空元素
        List<String> origins = new ArrayList<>();
        for (String o : allowedOrigins) {
            if (o != null && !o.trim().isEmpty()) {
                origins.add(o.trim());
            }
        }
        if (origins.isEmpty()) {
            log.info("跨域未启用（gb28181.cors.allowed-origins 为空）。"
                    + "前后端分离部署时请显式列出允许的来源。");
            return;
        }

        boolean wildcard = origins.contains("*");
        boolean credentials = allowCredentials && !wildcard;
        if (wildcard && allowCredentials) {
            log.warn("跨域配置为 \"*\" 且开启凭据：浏览器禁止该组合，"
                    + "已自动改为不带凭据。请改为显式列出前端地址。");
        }

        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // Authorization 是 Sa-Token 的 token 头，漏了它预检必然失败；
                // Content-Type 用于 JSON 请求体
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(credentials)
                .maxAge(maxAge);

        log.info("跨域已启用: origins={} credentials={}", origins, credentials);
    }
}
