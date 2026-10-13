package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SIP 客户端提供者。
 *
 * <p>{@link Gb28181MediaPlatform} 在 {@code stop()} 时会把内部引用清空，
 * 而 Spring 单例服务持有的是平台对象本身，故此处每次取用时重新解析，
 * 避免把已经失效的客户端缓存成字段。
 */
@Component
@RequiredArgsConstructor
public class Gb28181SipClientProvider {

    private final Gb28181MediaPlatform platform;

    /** 当前可用的 SIP 客户端；平台未启动时返回 {@code null}。 */
    public Gb28181SipClient client() {
        return platform.sipServer() == null ? null : platform.client();
    }

    /** 平台 20 位编码。 */
    public String platformId() {
        return platform.sipServer() == null ? null : platform.sipServer().getConfig().getServerId();
    }
}
