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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebRTC 播放接口。内嵌 ZLMediaKit 未注册 HTTP 播放路由（{@code /rtp/xxx.live.flv} 等），
 * 浏览器无法用 FLV/HLS 出画面，只有 WebRTC 通路可用。
 *
 * <p>典型用法：{@code POST /api/v1/webrtc/play?stream=CHANNEL_ID}，请求体为裸 SDP，
 * 从 JSON 的 {@code data.SDP} 取 answer。本接口不做登录校验：播放器通常带不了登录态，
 * 需要鉴权请在网关注入签名参数。
 */
@Slf4j
@RestController
@Tag(name = "WebRTC 协商", description = "SDP 交换（播放器免登录）")
@RequestMapping("/api/v1/webrtc")
@RequiredArgsConstructor
public class WebRtcController {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;
    private final StreamService streamService;

    /**
     * 交换 SDP。入参 {@code stream} 是业务坐标（{@code <设备编码>/<通道编码>}，设备直连可只传一个），
     * 而 ZLM 媒体源是 {@code app/stream}：单端口收流下 app 固定为 {@code rtp}、stream 为
     * {@code <设备编码>_<通道编码>}（见 {@code RtpSession#getZlmApp()}）。协商用的 {@code rtc://}
     * 地址必须与已注册媒体源完全一致，否则 ZLM 报 {@code stream not found}，
     * 故先用业务坐标取会话，再拿会话里的 ZLM 坐标协商。
     */
    @Operation(summary = "交换 SDP")
    @PostMapping(value = "/play", consumes = "*/*", produces = "application/json;charset=UTF-8")
    public ApiResponse<WebrtcPlayVo> play(@RequestParam String stream,
                                          @RequestParam(required = false) String peerId,
                                          @RequestBody String offerSdp) {
        WebrtcPlayVo vo = negotiate(stream, peerId, offerSdp);
        return ApiResponse.ok(vo);
    }

    /**
     * SRS 风格（LivePlayer 的 {@code Webrtc} tech 专用）SDP 交换。必须单开端点：
     * {@code @liveqing/liveplayer-v3} 写死了「请求体 base64(offer)、应答体 base64(answer) 纯文本」，
     * 且把 {@code webrtc://} 换成 {@code http}；{@code /play} 收发裸 SDP + JSON 包装，播放器解不出。
     * 地址：{@code webrtc://<host>/api/v1/webrtc/srs?stream=<设备编码>/<通道编码>}。
     * 失败返回空串（播放器只认 HTTP 状态码，会走 error 分支）。
     */
    @Operation(summary = "SRS 风格（LivePlayer 的 Webrtc tech 专用）SDP 交换")
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
        // 先按会话键精确匹配（覆盖直播/回放/下载三种会话）：回放键是 <设备>/<通道>_playback，
        // 只按通道查会命中同通道的直播会话，浏览器播到实时画面而不是选中的那段录像。
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
            // 回放流不在了就直说，不要回落到 startLive：那会拉起一路直播，
            // 用户以为「切协议」却看到实时画面。
            throw BizException.badRequest(
                    "回放流已关闭，无法切换协议，请重新点播该段录像");
        }
        // 用 ZLM 的实际 app/stream 协商；会话未建好时退回业务坐标（ZLM 侧也还没源，报错更贴近实际）
        String zlmApp = session == null ? deviceId : session.getZlmApp();
        String zlmStream = session == null ? channelId : session.getStream();
        String peer = peerId == null || peerId.isBlank()
                ? platform.webRtcService().nextPeerId() : peerId;
        try {
            String answer = platform.webRtcService().exchange(peer, zlmApp, zlmStream, offerSdp, 5);
            if (answer == null || answer.isBlank()) {
                throw BizException.badRequest("WebRTC 协商失败：媒体服务未返回 SDP，请确认流已建立");
            }
            /*
             * 协商成功后再读编码，而不是之前。
             *
             * ZLM 不做转码：answer 里的编码就是流本身的编码（H265 流 → a=rtpmap:98 H265/90000）。
             * 浏览器能不能解它，直接决定「有画面还是黑屏」：Chrome ≥136（需硬件 HEVC 解码）
             * 与 Safari 可以，Edge / Firefox / 国产浏览器不行。
             *
             * 顺序很重要：上面那句 exchange 可能顺手把流点播起来，媒体源要几百毫秒才注册，
             * 在它之前查只会查到 null（实测就是这么漏的）。所以放在协商之后。
             *
             * 只记录、只回填，**不因为 H265 就拒绝协商** —— 支持的浏览器上它是能正常播的。
             */
            String videoCodec = platform.mediaServer().videoCodecName(zlmApp, zlmStream);
            logCodec(zlmApp, zlmStream, videoCodec);
            WebrtcPlayVo vo = new WebrtcPlayVo();
            vo.setPeerId(peer);
            vo.setSdp(answer);
            vo.setVideoCodec(videoCodec);
            vo.setStreamId(deviceId + "/" + channelId);
            vo.setWebrtc(platform.mediaServer().playUrls(zlmApp, zlmStream).getWebrtc());
            return vo;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 把编码一起抛出去：ZLM 的原生错误只提「没有可解码的编码」，不会说是哪种，
            // 现场只能猜。失败路径这里再读一次（流此时已经存在，读得到）。
            String videoCodec = platform.mediaServer().videoCodecName(zlmApp, zlmStream);
            logCodec(zlmApp, zlmStream, videoCodec);
            throw BizException.badRequest("WebRTC 协商异常：" + e.getMessage()
                    + (videoCodec == null ? "" : "（该路流视频编码=" + videoCodec + "）"));
        }
    }

    /** 把视频编码记进日志；H265 单独提示浏览器支持面（这条是「黑屏」最常见的原因）。 */
    private void logCodec(String zlmApp, String zlmStream, String videoCodec) {
        if (ZlmMediaServer.isWebRtcVideoCodecRisky(videoCodec)) {
            log.info("WebRTC 协商：流 {}/{} 视频编码={}（H265/HEVC 仅 Chrome≥136 / Safari 支持，"
                    + "其余浏览器会失败或黑屏；需全兼容请把设备编码改为 H264）",
                    zlmApp, zlmStream, videoCodec);
        } else if (videoCodec != null) {
            log.debug("WebRTC 协商：流 {}/{} 视频编码={}", zlmApp, zlmStream, videoCodec);
        }
    }

    @Operation(summary = "WebRTC 播放参数")
    @GetMapping("/config")
    public ApiResponse<WebrtcConfigVo> config() {
        WebrtcConfigVo vo = new WebrtcConfigVo();
        vo.setWebRtcPort(platform.mediaServer() == null ? 0 : platform.mediaServer().getRtcPort());
        vo.setApp(props.getApp());
        vo.setMediaIp(props.getMediaIp());
        /*
         * 候选地址取「ZLM 实际生效的那个值」，不要在这里重新推断一遍：
         * 配置留空会回退 media-ip，填 auto 会探测本机主网卡 —— 规则只应该有一份，
         * 否则界面显示一个地址、answer 里是另一个地址，排查时会互相矛盾。
         */
        vo.setRtcExternIp(platform.mediaServer() == null
                ? null : platform.mediaServer().getRtcCandidateIp());
        vo.setNote("无 ICE 服务，浏览器与平台需同一局域网/可直连；"
                + "跨网段请自行部署 STUN/TURN 或改用 WebSocket-FLV。"
                + "若协商成功但画面全黑，先确认 rtcExternIp 是浏览器可达的地址");
        return ApiResponse.ok(vo);
    }
}
