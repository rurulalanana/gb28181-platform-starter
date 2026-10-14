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
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

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
        registry.addViewController("/{path:^(?!api|assets|record|error)[^\\.]*$}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!api|assets|record|error)[^\\.]*$}/**")
                .setViewName("forward:/index.html");
    }
}
