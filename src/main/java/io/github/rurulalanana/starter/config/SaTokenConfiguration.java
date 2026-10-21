package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

/**
 * Web 层配置：Sa-Token 登录校验 + 会话持久化 + 操作日志。
 *
 * <p>只对 {@code /api/v1/**} 做鉴权（GBS 风格的平台接口）；
 * 前端静态资源与 {@code /api/v1/webrtc/**}（播放器无法携带登录态）豁免。
 *
 * <p><b>注意</b>：不能用 {@code /api/**} 作为排除项——它会把
 * {@code /api/v1/**} 一并排除，导致鉴权完全失效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SaTokenConfiguration implements WebMvcConfigurer {

    private final OperationLogInterceptor operationLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        // 登录接口自身
                        "/api/v1/login",
                        // 流地址 / WebRTC 交换（播放器无法携带登录态）
                        "/api/v1/webrtc/**",
                        // 录像分享页：分享链接就是给「不登录平台的人」用的，
                        // 凭签名令牌换取录像信息（见 ShareTokenService）
                        "/api/v1/cloudrecord/share/**",
                        // 通道快照图片：直接放进 <img src> 加载，
                        // 而 <img> 请求带不了 Authorization 头，带鉴权就永远加载不出来。
                        // 快照本身是公开的通道缩略图，按静态资源处理。
                        //
                        // 模式必须是 */* 而非 **：前者只匹配「snap/{serial}/{code}.jpg」
                        // 两段路径，** 会连「snap/grab」（一段）一起放行 ——
                        // 抓拍要消耗设备带宽，不能变成匿名可调。
                        // （grab 接口内部还有一次 StpUtil.checkLogin() 兜底）
                        "/api/v1/cloudrecord/snap/*/*"
                );
        // 操作日志：记录 /api/v1/** 的接口调用（排除项在拦截器内部处理）
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/v1/**");
    }

    /**
     * Sa-Token 会话持久层：改用 PostgreSQL 存储（{@code sa_token_store} 表）。
     *
     * <p>Sa-Token 的 Spring Boot 集成会自动发现容器中的 {@link SaTokenDao} 并接管默认
     * 内存实现，因此声明这个 Bean 即生效：服务重启后已签发的 token 依然有效，
     * 用户不必重新登录；登出（logout）会同步删除库中的会话记录。
     */
    @Bean
    public SaTokenDao saTokenDao(DataSource dataSource) {
        log.info("Sa-Token 会话持久化已启用：sa_token_store（PostgreSQL）");
        return new SaTokenJdbcDao(dataSource);
    }
}
