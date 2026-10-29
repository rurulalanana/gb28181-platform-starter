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
     * <p><b>入参是业务坐标，不是 ZLM 坐标</b>：单端口收流模式下 ZLM 的 app 固定为
     * {@code rtp}、stream 为 {@code <设备编码>_<通道编码>}（见
     * {@code RtpSession#getZlmApp()}），与业务坐标不一致。协商用的 {@code rtc://}
     * 地址必须与已注册的媒体源完全一致，否则 ZLM 回调直接报
     * {@code stream not found}。故此处先用业务坐标取会话，再拿会话里的
     * ZLM 坐标去协商。
     *
     * @param stream {@code <设备编码>/<通道编码>}；设备直连（设备编码即通道编码）
     *               时可只传一个编码
     */
    @PostMapping(value = "/play", consumes = "*/*", produces = "application/json;charset=UTF-8")
    public ApiResponse<WebrtcPlayVo> play(@RequestParam String stream,
                                          @RequestParam(required = false) String peerId,
                                          @RequestBody String offerSdp) {
        WebrtcPlayVo vo = negotiate(stream, peerId, offerSdp);
        return ApiResponse.ok(vo);
    }

    /**
     * SRS 风格（LivePlayer 的 {@code Webrtc} tech 专用）SDP 交换。
     *
     * <p><b>为什么要单开一个端点</b>：{@code @liveqing/liveplayer-v3} 内置的
     * {@code Webrtc} tech 写死了这套约定（见 {@code liveplayer-lib.min.js}）：
     * <pre>
     * const xhr = new XMLHttpRequest();
     * xhr.open('POST', url.replace('webrtc', 'http'), true);   // webrtc:// → http://
     * xhr.send(btoa(pc.localDescription.sdp));                 // 请求体 = base64(offer)
     * pc.setRemoteDescription({ type:'answer', sdp: atob(xhr.responseText) });
     * </pre>
     * 即：<b>两个方向都是 base64</b> —— 请求体是 base64 编码的 offer，
     * 应答体必须是 base64 编码的 answer 纯文本。本项目原有的 {@code /play}
     * 收发都是裸 SDP + JSON 包装，播放器解不出来，故此处适配。
     *
     * <p>地址拼法（前端 {@code useLivePlayer.js} 已按此生成）：
     * {@code webrtc://<host>/api/v1/webrtc/srs?stream=<设备编码>/<通道编码>}
     * —— 播放器把协议头替换成 {@code http} 后正是本接口。
     *
     * @param offerSdpBase64 base64 编码的 offer SDP
     * @return base64 编码的 answer SDP；失败时返回空串（播放器按 error 处理）
     */
    @PostMapping(value = "/srs", consumes = "*/*", produces = "text/plain;charset=UTF-8")
    public String srsWhep(@RequestParam String stream,
                          @RequestParam(required = false) String peerId,
                          @RequestBody String offerSdpBase64) {
        try {
            String offerSdp = new String(
                    java.util.Base64.getMimeDecoder().decode(offerSdpBase64.trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
            WebrtcPlayVo vo = negotiate(stream, peerId, offerSdp);
            return java.util.Base64.getEncoder()
                    .encodeToString(vo.getSdp().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 播放器只认 HTTP 状态码，这里降级成空串让它走 error 分支
            log.warn("WebRTC(SRS 风格) 交换失败: stream={} err={}", stream, e.getMessage());
            return "";
        }
    }

    /**
     * 统一的 SDP 交换实现。
     *
     * @param stream  {@code <设备编码>/<通道编码>}
     * @param peerId  对端标识，空则服务端分配
     * @param offerSdp 浏览器 offer
     */
    private WebrtcPlayVo negotiate(String stream, String peerId, String offerSdp) {
        if (platform.webRtcService() == null || platform.mediaServer() == null) {
            throw BizException.badRequest("媒体服务未启动，无法进行 WebRTC 交换");
        }
        String deviceId;
        String channelId;
        if (stream != null && stream.contains("/")) {
            String[] parts = stream.split("/", 2);
            deviceId = parts[0];
            channelId = parts[1];
        } else {
            // 单个编码 = 设备直连（设备编码即通道编码）
            deviceId = stream;
            channelId = stream;
        }
        /*
         * 先按<b>会话键</b>精确匹配（同时覆盖直播、回放、下载三种会话）。
         *
         * <p>回放的会话键是 {@code <设备>/<通道>_playback}，若只按通道查，
         * 要么查不到、要么命中同通道的<b>直播</b>会话 —— 后者会让浏览器
         * 播到实时画面而不是选中的那段录像。
         */
        RtpSession session = platform.sessionManager().getByKey(deviceId + "/" + channelId);
        boolean playback = channelId.endsWith("_playback");
        if (session == null && !playback) {
            session = platform.sessionManager().getByChannel(deviceId, channelId);
            // 流尚未建立时自动点播（避免浏览器拿到空流）
            if (session == null) {
                try {
                    streamService.startLive(deviceId, channelId);
                } catch (Exception e) {
                    log.warn("WebRTC 播放前自动点播失败: device={} channel={} err={}",
                            deviceId, channelId, e.getMessage());
                }
                session = platform.sessionManager().getByChannel(deviceId, channelId);
            }
        }
        if (playback && (session == null || RtpSession.CLOSED.equals(session.getState()))) {
            /*
             * 回放流不在了就直说，不要回落到 startLive：
             * 那会拉起一路直播，用户以为「切协议」却看到实时画面。
             * 前端拿到这句提示会提示用户重新点播该段。
             */
            throw BizException.badRequest(
                    "回放流已关闭，无法切换协议，请重新点播该段录像");
        }
        // 用 ZLM 的实际 app/stream 协商；会话还没建好时退回业务坐标
        // （此时 ZLM 侧也还没有源，报错信息更贴近实际）
        String zlmApp = session == null ? deviceId : session.getZlmApp();
        String zlmStream = session == null ? channelId : session.getStream();
        String peer = peerId == null || peerId.isBlank()
                ? platform.webRtcService().nextPeerId() : peerId;
        try {
            String answer = platform.webRtcService().exchange(peer, zlmApp, zlmStream, offerSdp, 5);
            if (answer == null || answer.isBlank()) {
                throw BizException.badRequest("WebRTC 协商失败：媒体服务未返回 SDP，请确认流已建立");
            }
            WebrtcPlayVo vo = new WebrtcPlayVo();
            vo.setPeerId(peer);
            vo.setSdp(answer);
            vo.setStreamId(deviceId + "/" + channelId);
            vo.setWebrtc(platform.mediaServer().playUrls(zlmApp, zlmStream).getWebrtc());
            return vo;
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
