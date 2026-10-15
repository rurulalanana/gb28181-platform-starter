package io.github.rurulalanana.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端单页应用（SPA）路由回退。
 *
 * <p>Vue Router 使用 history 模式，直接访问 {@code /dashboard}、{@code /devices}
 * 等前端路由时，Spring Boot 找不到对应控制器会返回 404。
 * 这里把非 API、非静态资源的路径统一转发到 {@code index.html}，由前端路由接管。
 *
 * <p>注意：只转发「无扩展名」的路径（前端路由形如 {@code /devices}），
 * 静态资源（{@code /assets/xxx.js}）与接口（{@code /api/v1/xxx}）不受影响。
 *
 * <p><b>排除清单必须覆盖所有一级静态资源目录</b>，否则形如
 * {@code /jessibuca/decoder.js} 这类「目录 + 带扩展名文件」的路径会被
 * 下面的通配规则吃掉、错误地返回 {@code index.html}
 * （表现为播放器脚本请求拿到一段 HTML，jessibuca 无法加载）。
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

    /** 一级路径白名单：这些前缀下的请求交给静态资源处理器，不做 SPA 回退。 */
    private static final String STATIC_PREFIXES = "api|assets|record|error|jessibuca";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 一级路由（前端菜单路径）
        String[] routes = {
                "dashboard", "live", "control", "devices", "channels", "playback",
                "cloud", "alarms", "cascade", "users", "settings"
        };
        for (String route : routes) {
            registry.addViewController("/" + route).setViewName("forward:/index.html");
        }
        // 未知前端路由（如带参数的子路径）兜底
        registry.addViewController("/{path:^(?!" + STATIC_PREFIXES + ")[^\\.]*$}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!" + STATIC_PREFIXES + ")[^\\.]*$}/**")
                .setViewName("forward:/index.html");
    }
}
