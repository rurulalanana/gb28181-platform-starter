package io.github.rurulalanana.starter.config;

import io.github.rurulalanana.starter.web.DahuaTalkWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册大华对讲音频下行的 WebSocket 端点。
 *
 * <p>路径刻意放在 {@code /api/v1/dahua/talk/audio}：与 REST 同一前缀，
 * 反代只需为 {@code /api/v1/**} 打开 {@code Upgrade} 头即可，不用再维护一条规则。
 *
 * <p><b>为什么 {@code setAllowedOriginPatterns("*")}</b>：WebSocket 握手不受 CORS 保护，
 * Spring 默认只允许同源；平台既有「jar 内前端同源部署」也有「nginx 反代 + 自定义域」
 * 两种形态，写死域名会让后者连不上。安全性由 handler 里的 token 校验承担
 * （见 {@link DahuaTalkWebSocketHandler}），不放行任何匿名连接。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class DahuaTalkWebSocketConfiguration implements WebSocketConfigurer {

    private final DahuaTalkWebSocketHandler dahuaTalkWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dahuaTalkWebSocketHandler, "/api/v1/dahua/talk/audio")
                .setAllowedOriginPatterns("*");
    }
}
