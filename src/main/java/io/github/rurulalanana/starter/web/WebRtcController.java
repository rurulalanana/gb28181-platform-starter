package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.WebrtcConfigVo;
import io.github.rurulalanana.starter.vo.WebrtcPlayVo;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebRTC 播放接口。
 *
 * <p><b>为什么必须走 WebRTC</b>：本项目用内嵌 ZLMediaKit，其 HTTP 播放路由
 * （{@code /rtp/xxx.live.flv} 等）未注册，浏览器无法用 FLV/HLS 出画面；
 * 只有 WebRTC 通路是通的。
 *
 * <p>典型用法（浏览器侧）：
 * <pre>
 * const pc = new RTCPeerConnection({ iceServers: [] });
 * pc.addTransceiver('video', { direction: 'recvonly' });
 * pc.addTransceiver('audio', { direction: 'recvonly' });
 * const offer = await pc.createOffer();
 * await pc.setLocalDescription(offer);
 * const res = await fetch('/api/v1/webrtc/play?stream=CHANNEL_ID', {
 *   method: 'POST',
 *   headers: { 'Content-Type': 'application/sdp' },
 *   body: offer.sdp
 * });
 * const { data } = await res.json();
 * await pc.setRemoteDescription({ type: 'answer', sdp: data.SDP });
 * </pre>
 *
 * <p>本接口不做登录校验：播放器（{@code <video>} / 第三方播放器）通常无法携带
 * 登录态。若需鉴权，请在网关注入签名参数。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webrtc")
@RequiredArgsConstructor
public class WebRtcController {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;
    private final StreamService streamService;

    /**
     * 交换 SDP。
     *
     * <p>ZLM 媒体源标识是 {@code app/stream}，本平台约定 {@code app = 设备编码}、
     * {@code stream = 通道编码}（通道编码跨设备可重复，必须带上设备编码）。
     *
     * @param stream {@code <设备编码>/<通道编码>}；设备直连（设备编码即通道编码）
     *               时可只传一个编码
     */
    @PostMapping(value = "/play", consumes = "*/*", produces = "application/json;charset=UTF-8")
    public ApiResponse<WebrtcPlayVo> play(@RequestParam String stream,
                                          @RequestParam(required = false) String peerId,
                                          @RequestBody String offerSdp) {
        if (platform.webRtcService() == null || platform.mediaServer() == null) {
            throw BizException.badRequest("媒体服务未启动，无法进行 WebRTC 交换");
        }
        String app;
        String channel;
        if (stream != null && stream.contains("/")) {
            String[] parts = stream.split("/", 2);
            app = parts[0];
            channel = parts[1];
        } else {
            // 单个编码 = 设备直连（设备编码即通道编码）
            app = stream;
            channel = stream;
        }
        // 若流尚未建立，自动点播（避免浏览器拿到空流）
        RtpSession session = platform.sessionManager().getByChannel(app, channel);
        if (session == null) {
            try {
                streamService.startLive(app, channel);
            } catch (Exception e) {
                log.warn("WebRTC 播放前自动点播失败: app={} channel={} err={}",
                        app, channel, e.getMessage());
            }
        }
        String peer = peerId == null || peerId.isBlank()
                ? platform.webRtcService().nextPeerId() : peerId;
        try {
            String answer = platform.webRtcService().exchange(peer, app, channel, offerSdp, 5);
            if (answer == null || answer.isBlank()) {
                throw BizException.badRequest("WebRTC 协商失败：媒体服务未返回 SDP，请确认流已建立");
            }
            WebrtcPlayVo vo = new WebrtcPlayVo();
            vo.setPeerId(peer);
            vo.setSdp(answer);
            vo.setStreamId(app + "/" + channel);
            vo.setWebrtc(platform.mediaServer().playUrls(app, channel).getWebrtc());
            return ApiResponse.ok(vo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("WebRTC 协商异常：" + e.getMessage());
        }
    }

    /** WebRTC 播放参数。 */
    @GetMapping("/config")
    public ApiResponse<WebrtcConfigVo> config() {
        WebrtcConfigVo vo = new WebrtcConfigVo();
        vo.setWebRtcPort(platform.mediaServer() == null ? 0 : platform.mediaServer().getRtcPort());
        vo.setApp(props.getApp());
        vo.setMediaIp(props.getMediaIp());
        vo.setNote("无 ICE 服务，浏览器与平台需同一局域网/可直连；"
                + "跨网段请自行部署 STUN/TURN 或改用 WebSocket-FLV");
        return ApiResponse.ok(vo);
    }
}
