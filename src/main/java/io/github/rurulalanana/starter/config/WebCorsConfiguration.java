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
 * <p>默认关闭：{@code allowed-origins} 为空 = 不下发任何跨域响应头。前端产物打包进 jar 同源提供，
 * 无需 CORS；默认放开（尤其 {@code "*"}）等于任何网站都能带着用户登录态调本平台接口，是 CSRF 放大面。
 *
 * <p>浏览器禁止 {@code "*"} + {@code allowCredentials=true}（控制台报
 * {@code must not be '*'}），本类遇 {@code "*"} 会自动回退为不带凭据并在启动日志警告。
 * 跨域前浏览器先发 {@code OPTIONS} 预检，Sa-Token 的 {@code Authorization} 头必须在 allowedHeaders 里。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "gb28181.cors")
public class WebCorsConfiguration implements WebMvcConfigurer {

    /**
     * 允许的来源，逗号分隔（如 {@code http://192.168.1.10:8081}）。留空 = 关闭跨域，
     * 支持通配子域 {@code https://*.example.com}。
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 预检结果缓存秒数（默认 1 小时，减少 OPTIONS 往返）。 */
    private long maxAge = 3600L;

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
                // Authorization 是 Sa-Token 的 token 头，漏了它 OPTIONS 预检必然失败
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(credentials)
                .maxAge(maxAge);

        log.info("跨域已启用: origins={} credentials={}", origins, credentials);
    }
}
