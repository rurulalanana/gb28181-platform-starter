package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.vo.RelayVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播转推服务。
 *
 * <p>用 ZLMediaKit 的 {@code mk_pusher_*} 把本地流推给外部 RTMP/RTSP 地址。
 * 推流器句柄需要保留以便停止，因此这里维护一份「通道 → CDN」的注册表。
 *
 * <p><b>限制</b>：嵌入式模式只能推 RTMP/RTSP 等 ZLM 支持的协议；
 * 推流器状态无回调查询接口，只能记录「是否已创建」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelayService {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;
    private final StreamService streamService;

    /** 通道 → (CDN 地址 → 推流器)。 */
    private final Map<String, Map<String, RelayEntry>> relays = new ConcurrentHashMap<>();

    /** 单条转推记录。 */
    public record RelayEntry(String cdn, String localStream, Object pusher, LocalDateTime startAt) {
    }

    /**
     * 启动转推。
     *
     * @param channelId 通道编号（必须已在直播）
     * @param cdn       目标地址，如 {@code rtmp://live.example.com/app/stream}
     */
    public RelayVo start(String channelId, String cdn) {
        if (cdn == null || cdn.isBlank()) {
            throw BizException.badRequest("cdn 不能为空");
        }
        RtpSession session = streamService.session(channelId);
        if (session == null) {
            throw BizException.badRequest("通道未在直播，请先调用 stream/start");
        }
        if (platform.mediaServer() == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        Map<String, RelayEntry> existing = relays.computeIfAbsent(channelId,
                k -> new ConcurrentHashMap<>());
        RelayEntry old = existing.remove(cdn);
        if (old != null) {
            release(old);
        }

        String localUrl = localStreamUrl(session);
        var api = ZlmMediaServer.api();
        // mk_pusher_create(url, app, stream, schema)：schema 为空表示由 url 协议自动推断
        var pusher = api.mk_pusher_create(cdn, props.getApp(),
                "relay_" + channelId, "");
        if (pusher == null) {
            throw BizException.badRequest("创建推流器失败，请检查 CDN 地址是否可达");
        }
        api.mk_pusher_publish(pusher, localUrl);
        RelayEntry entry = new RelayEntry(cdn, session.getStream(), pusher, LocalDateTime.now());
        existing.put(cdn, entry);
        log.info("转推已启动: channel={} 本地流={} → CDN={}", channelId, localUrl, cdn);
        return toView(entry, channelId);
    }

    /** 停止转推。 */
    public boolean stop(String channelId, String cdn) {
        Map<String, RelayEntry> existing = relays.get(channelId);
        if (existing == null) {
            return false;
        }
        RelayEntry entry = existing.remove(cdn);
        if (entry == null) {
            return false;
        }
        release(entry);
        log.info("转推已停止: channel={} cdn={}", channelId, cdn);
        return true;
    }

    /** 停止某通道的全部转推（通道下线时调用）。 */
    public int stopAll(String channelId) {
        Map<String, RelayEntry> existing = relays.remove(channelId);
        if (existing == null || existing.isEmpty()) {
            return 0;
        }
        existing.values().forEach(this::release);
        log.info("通道 {} 的全部转推已停止，共 {} 路", channelId, existing.size());
        return existing.size();
    }

    /** 某通道的转推列表。 */
    public List<RelayVo> list(String channelId) {
        Map<String, RelayEntry> existing = relays.get(channelId);
        if (existing == null || existing.isEmpty()) {
            return new ArrayList<>();
        }
        List<RelayVo> out = new ArrayList<>();
        existing.values().forEach(e -> out.add(toView(e, channelId)));
        return out;
    }

    /** 转推路数。 */
    public int count(String channelId) {
        Map<String, RelayEntry> existing = relays.get(channelId);
        return existing == null ? 0 : existing.size();
    }

    // ==================== 内部 ====================

    /** 本地拉流地址（优先 RTSP，ZLM 内部拉流最稳）。 */
    private String localStreamUrl(RtpSession session) {
        int rtspPort = platform.mediaServer().getRtspPort();
        String ip = props.getMediaIp() == null || props.getMediaIp().isBlank()
                ? "127.0.0.1" : props.getMediaIp();
        // 本地拉流用回环地址，避免绕一圈网卡。
        // 路径必须是 app/stream（app=设备编码、stream=通道编码），只写通道编码会 404。
        return "rtsp://127.0.0.1:" + rtspPort + "/" + session.getZlmApp() + "/" + session.getStream()
                + "?ip=" + ip;
    }

    private void release(RelayEntry entry) {
        try {
            if (entry.pusher() != null) {
                ZlmMediaServer.api().mk_pusher_release(
                        (com.aizuda.zlm4j.structure.MK_PUSHER) entry.pusher());
            }
        } catch (Exception e) {
            log.warn("释放推流器失败: cdn={} err={}", entry.cdn(), e.getMessage());
        }
    }

    private RelayVo toView(RelayEntry e, String channelId) {
        RelayVo vo = new RelayVo();
        vo.setChannelId(channelId);
        vo.setStreamId(e.localStream());
        vo.setCdn(e.cdn());
        vo.setStartAt(TimeFormats.toDisplay(e.startAt()));
        return vo;
    }
}
