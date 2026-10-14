package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：Sa-Token 登录校验。
 *
 * <p>只对 {@code /api/v1/**} 做鉴权（GBS 风格的平台接口）；
 * 前端静态资源与 {@code /api/v1/webrtc/**}（播放器无法携带登录态）豁免。
 *
 * <p><b>注意</b>：不能用 {@code /api/**} 作为排除项——它会把
 * {@code /api/v1/**} 一并排除，导致鉴权完全失效。
 */
@Configuration
public class SaTokenConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        // 登录接口自身
                        "/api/v1/login",
                        // 流地址 / WebRTC 交换（播放器无法携带登录态）
                        "/api/v1/webrtc/**"
                );
    }
}
