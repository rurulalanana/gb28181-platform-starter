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
 * 只对 {@code /api/v1/**} 鉴权；静态资源与 {@code /api/v1/webrtc/**}（播放器带不了登录态）豁免。
 * <b>不能用 {@code /api/**} 作为排除项</b>：它会把 {@code /api/v1/**} 一并排除，鉴权完全失效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SaTokenConfiguration implements WebMvcConfigurer {

    private final OperationLogInterceptor operationLogInterceptor;
    /** 写操作权限守卫：非 GET 请求默认要求写权限，具体权限点见前缀规则表。 */
    private final WritePermissionInterceptor writePermissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/login",
                        // 验证码：它就是用来保护登录接口的，必须先能访问
                        "/api/v1/captcha/**",
                        // 流地址 / WebRTC 交换：播放器无法携带登录态
                        "/api/v1/webrtc/**",
                        // 录像分享页：凭签名令牌换取录像信息（见 ShareTokenService）
                        "/api/v1/cloudrecord/share/**",
                        // 直播分享页：外部人员直接打开，无账号无 token；只读且不暴露 SSRC/callId
                        "/api/v1/share/**",
                        // 录像地址鉴权端点：nginx auth_request 子请求带不了登录态，安全性由签名保证。
                        // 必须排除，否则所有录像（包括正常播放）都 403。
                        "/api/v1/record/auth",
                        // 通道快照：直接进 <img src>，带鉴权就永远加载不出来。
                        // 必须是 */* 而非 **：后者会连 snap/grab 一起放行，而抓拍要消耗设备带宽
                        // （grab 接口内部还有一次 StpUtil.checkLogin() 兜底）。
                        "/api/v1/cloudrecord/snap/*/*",
                        // 设备直传抓图：设备没有登录态，且大华只能填一个 URL、
                        // 不支持自定义请求头，令牌只能放 query。
                        // 安全性由端点自身的令牌校验保证（未配令牌时它直接拒绝接收）。
                        "/api/v1/dahua/snapupload",
                        // 对讲音频下行（WebSocket 握手）：浏览器 new WebSocket() 带不了
                        // Authorization 头，令牌只能拼在 query 上（ws://...?talkId=&token=）。
                        // 放行的只是握手请求；真正的鉴权在 DahuaTalkWebSocketHandler 里
                        // 用 StpUtil.getLoginIdByToken(token) 再做一次，无效连接直接 1008 关闭。
                        "/api/v1/dahua/talk/audio"
                );
        // 操作日志：记录 /api/v1/** 的接口调用（排除项在拦截器内部处理）
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/v1/**");
        // 写操作权限守卫：必须排在登录校验之后（依赖已登录），
        // 排在操作日志之前（越权请求被拒绝时也要留痕，见下面的说明）。
        registry.addInterceptor(writePermissionInterceptor)
                .addPathPatterns("/api/v1/**");
    }

    /**
     * Sa-Token 会话持久层：改用 PostgreSQL 存储（{@code sa_token_store}）。Spring Boot 集成会自动发现
     * 容器中的 {@link SaTokenDao} 并接管内存实现，重启后已签发的 token 仍有效，登出会同步删库。
     */
    @Bean
    public SaTokenDao saTokenDao(DataSource dataSource) {
        log.info("Sa-Token 会话持久化已启用：sa_token_store（PostgreSQL）");
        return new SaTokenJdbcDao(dataSource);
    }
}
