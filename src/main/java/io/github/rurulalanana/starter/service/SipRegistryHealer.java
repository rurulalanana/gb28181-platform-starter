package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.device.DeviceRegistry;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.SipPeer;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SIP 注册表自愈。
 *
 * <p><b>为什么需要</b>：{@code gb28181-sip} 的设备注册表是<b>纯内存</b>的。
 * 平台重启后注册表被清空，而国标设备的心跳周期通常是 60 秒、注册有效期 3600 秒，
 * 设备<b>不会</b>因为平台重启而重新 REGISTER——于是出现很别扭的状态：
 * <ul>
 *   <li>心跳照常到达，日志里能看到设备"在线"</li>
 *   <li>但注册表里没有该设备，所有下行信令（Catalog / DeviceInfo / INVITE / PTZ）
 *       都会报「设备未注册或会话已失效」</li>
 * </ul>
 *
 * <p>原库的 {@code handleKeepAlive} 只对<b>已存在</b>的会话做 {@code touch()}，
 * 不会新建。这里补上：心跳到达而注册表无此设备时，用心跳携带的地址
 * 重建一个会话，让平台在下次设备 REGISTER 之前就能正常下发信令。
 *
 * <p>重建的会话有效期取心跳来源的默认值（{@link #DEFAULT_EXPIRES}），
 * 等设备自然续注册后会被真实有效期覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SipRegistryHealer {

    /** 重建会话的默认有效期（秒）。取 3600 与国标设备默认注册有效期一致。 */
    private static final int DEFAULT_EXPIRES = 3600;

    private final Gb28181MediaPlatform platform;

    /**
     * 心跳到达时调用：注册表缺该设备则重建会话。
     *
     * <p>同步执行（仅内存操作），不会阻塞 SIP 线程。
     *
     * @return true 表示本次执行了重建
     */
    public boolean healOnKeepAlive(KeepAliveEvent event) {
        if (platform.sipServer() == null) {
            return false;
        }
        DeviceRegistry registry = platform.sipServer().getRegistry();
        if (registry == null) {
            return false;
        }
        String deviceId = event.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        if (registry.get(deviceId) != null) {
            // 注册表里有，正常路径已 touch，无需处理
            return false;
        }
        SipPeer peer = event.getPeer();
        if (peer == null || peer.getIp() == null) {
            log.warn("心跳来自未注册设备 {} 且无法确定地址，跳过注册表自愈", deviceId);
            return false;
        }
        long now = System.currentTimeMillis();
        DeviceSession session = DeviceSession.builder()
                .deviceId(deviceId)
                .ip(peer.getIp())
                .port(peer.getPort())
                .transport(peer.getTransport() == null ? "UDP" : peer.getTransport())
                .expires(DEFAULT_EXPIRES)
                .registerTime(now)
                .lastKeepAlive(now)
                .domain(peer.getDomain())
                .platform(false)
                .build();
        registry.register(session);
        log.info("注册表自愈：设备 {} 未在注册表中（平台重启后常见），"
                        + "已按心跳地址 {}:{} 重建会话",
                deviceId, peer.getIp(), peer.getPort());
        return true;
    }

    /** 设备是否在 SIP 注册表中（诊断用）。 */
    public boolean inRegistry(String deviceId) {
        if (platform.sipServer() == null || platform.sipServer().getRegistry() == null) {
            return false;
        }
        return platform.sipServer().getRegistry().get(deviceId) != null;
    }

    /**
     * 按数据库里记录的地址重建会话（供下行信令前调用）。
     *
     * <p>心跳自愈只覆盖「心跳先到」的时机；而平台重启后到下一次心跳之间可能有
     * 最多一个心跳周期（通常 60 秒）的空窗，这期间点播会直接失败。
     * 这里用数据库里存的 {@code remote_ip / remote_port} 兜底，
     * 让重启后立刻点播也能成功。
     *
     * @param deviceId 设备编码
     * @param ip       设备 SIP 地址（数据库里的 remote_ip）
     * @param port     设备 SIP 端口（数据库里的 remote_port）
     * @param transport 传输协议，可为 null（默认 UDP）
     * @return true 表示本次执行了重建
     */
    public boolean healFromDb(String deviceId, String ip, Integer port, String transport) {
        if (platform.sipServer() == null || platform.sipServer().getRegistry() == null) {
            return false;
        }
        if (deviceId == null || deviceId.isBlank() || ip == null || ip.isBlank()) {
            return false;
        }
        DeviceRegistry registry = platform.sipServer().getRegistry();
        if (registry.get(deviceId) != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        registry.register(DeviceSession.builder()
                .deviceId(deviceId)
                .ip(ip)
                .port(port == null ? 5060 : port)
                .transport(transport == null || transport.isBlank() ? "UDP" : transport)
                .expires(DEFAULT_EXPIRES)
                .registerTime(now)
                .lastKeepAlive(now)
                .platform(false)
                .build());
        log.info("注册表自愈：设备 {} 不在注册表中，已按数据库地址 {}:{} 重建会话",
                deviceId, ip, port);
        return true;
    }
}
