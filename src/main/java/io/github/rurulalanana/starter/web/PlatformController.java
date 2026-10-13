package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.vo.NativeDeviceVo;
import io.github.rurulalanana.starter.vo.NativeOkVo;
import io.github.rurulalanana.starter.vo.NativePlayUrlsVo;
import io.github.rurulalanana.starter.vo.NativeRecordVo;
import io.github.rurulalanana.starter.vo.NativeRecordingPlayVo;
import io.github.rurulalanana.starter.vo.NativeRecordingVo;
import io.github.rurulalanana.starter.vo.NativeSessionItemVo;
import io.github.rurulalanana.starter.vo.NativeSessionVo;
import io.github.rurulalanana.starter.vo.NativeStatusVo;
import io.github.rurulalanana.starter.vo.NativeWebrtcConfigVo;
import io.github.rurulalanana.starter.vo.NativeWebrtcVo;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.Recording;
import io.github.rurulalanana.gb28181.sip.zlm.RecordingService;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 原生测试用 REST API：设备、点播、回放、下载、录像、WebRTC。
 *
 * <p>这一组接口是<b>非 GBS 风格</b>的调试接口，供 {@code static/index.html} 使用，
 * 字段名统一为小驼峰。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlatformController {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;

    // ==================== 状态 ====================

    @GetMapping("/status")
    public NativeStatusVo status() {
        NativeStatusVo vo = new NativeStatusVo();
        vo.setPlatformId(props.getPlatformId());
        vo.setSip(props.getSipHost() + ":" + props.getSipPort());
        vo.setMediaIp(platform.mediaServer() == null ? "" : props.getMediaIp());
        if (platform.mediaServer() != null) {
            vo.setHttp(platform.mediaServer().getHttpPort());
            vo.setRtmp(platform.mediaServer().getRtmpPort());
            vo.setRtsp(platform.mediaServer().getRtspPort());
            vo.setWebrtc(platform.mediaServer().getRtcPort());
        }
        vo.setSessionCount(platform.sessionManager() == null
                ? 0 : platform.sessionManager().sessionCount());
        vo.setRecordCount(platform.recordingService() == null
                ? 0 : platform.recordingService().all().size());
        vo.setNote("嵌入式模式下 ZLM 的 HTTP 播放路由未注册，浏览器出画面请用 WebRTC");
        return vo;
    }

    /** 平台注册表中的在线设备。 */
    @GetMapping("/devices")
    public List<NativeDeviceVo> devices() {
        List<NativeDeviceVo> list = new ArrayList<>();
        if (platform.sipServer() != null && platform.sipServer().getRegistry() != null) {
            platform.sipServer().getRegistry().listAll().forEach(session -> {
                NativeDeviceVo vo = new NativeDeviceVo();
                vo.setDeviceId(session.getDeviceId());
                vo.setAddress(session.getAddress());
                vo.setOnline(platform.sipServer().getRegistry().isOnline(session.getDeviceId()));
                vo.setRegisterTime(session.getRegisterTime());
                list.add(vo);
            });
        }
        return list;
    }

    // ==================== 媒体会话 ====================

    /** 平台主动点播（标准 9.2）。 */
    @PostMapping("/play/{deviceId}")
    public NativeSessionVo play(@PathVariable String deviceId) {
        RtpSession session = platform.mediaService().play(deviceId);
        return sessionInfo(session);
    }

    /** 检索设备录像（标准 9.8），返回录像段列表。 */
    @GetMapping("/records/{deviceId}")
    public List<NativeRecordVo> queryRecords(@PathVariable String deviceId,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to)
            throws Exception {
        String start = from == null ? "2000-01-01T00:00:00" : from;
        String end = to == null ? "2099-12-31T23:59:59" : to;
        CompletableFuture<List<NativeRecordVo>> future = new CompletableFuture<>();
        platform.mediaService().queryRecordInfo(deviceId, start, end, items -> {
            List<NativeRecordVo> out = new ArrayList<>();
            for (RecordItem item : items) {
                NativeRecordVo vo = new NativeRecordVo();
                vo.setDeviceId(item.getDeviceId());
                vo.setName(item.getName());
                vo.setFilePath(item.getFilePath());
                vo.setType(item.getType());
                vo.setStartTime(RecordItem.formatTime(item.getStartTime()));
                vo.setEndTime(RecordItem.formatTime(item.getEndTime()));
                vo.setFileSize(item.getFileSize());
                out.add(vo);
            }
            future.complete(out);
        });
        // 模拟设备/不响应设备超时返回空列表
        return future.get(3, TimeUnit.SECONDS);
    }

    /** 发起历史回放（标准 9.8）：filePath 取自检索结果。 */
    @PostMapping("/playback/{deviceId}")
    public NativeSessionVo playback(@PathVariable String deviceId,
                                    @RequestParam String filePath) {
        RecordItem item = RecordItem.ofFilePath(deviceId, filePath);
        RtpSession session = platform.mediaService().playback(deviceId, item);
        return sessionInfo(session);
    }

    /** 回放控制（标准附录 D）。 */
    @PostMapping("/playback/{deviceId}/control")
    public NativeOkVo playbackControl(@PathVariable String deviceId,
                                      @RequestParam String callId,
                                      @RequestParam(defaultValue = "pause") String command,
                                      @RequestParam(required = false) Long seek,
                                      @RequestParam(defaultValue = "1.0") float scale) {
        Gb28181MediaService.PlaybackCommand cmd;
        switch (command.toLowerCase()) {
            case "play" -> cmd = Gb28181MediaService.PlaybackCommand.PLAY;
            case "pause" -> cmd = Gb28181MediaService.PlaybackCommand.PAUSE;
            case "seek" -> cmd = Gb28181MediaService.PlaybackCommand.SEEK;
            case "speed" -> cmd = Gb28181MediaService.PlaybackCommand.SPEED;
            default -> cmd = Gb28181MediaService.PlaybackCommand.STOP;
        }
        platform.mediaService().playbackControl(deviceId, callId, cmd,
                seek == null ? null : new java.util.Date(seek), scale);
        NativeOkVo vo = NativeOkVo.of(true);
        vo.setCommand(command);
        return vo;
    }

    /** 文件下载（标准 9.9）。 */
    @PostMapping("/download/{deviceId}")
    public NativeSessionVo download(@PathVariable String deviceId,
                                    @RequestParam String filePath) {
        RtpSession session = platform.mediaService().download(deviceId, filePath);
        return sessionInfo(session);
    }

    /** 停止点播并释放收流端口。 */
    @PostMapping("/stop/{deviceId}")
    public NativeOkVo stop(@PathVariable String deviceId) {
        platform.mediaService().stop(deviceId);
        NativeOkVo vo = NativeOkVo.of(true);
        vo.setDeviceId(deviceId);
        return vo;
    }

    /** 当前会话 + 播放地址。 */
    @GetMapping("/stream/{deviceId}")
    public NativeSessionVo stream(@PathVariable String deviceId) {
        RtpSession session = platform.sessionManager().getByChannel(deviceId);
        if (session == null) {
            NativeSessionVo vo = new NativeSessionVo();
            vo.setStreaming(false);
            vo.setDeviceId(deviceId);
            return vo;
        }
        return sessionInfo(session);
    }

    @GetMapping("/sessions")
    public List<NativeSessionItemVo> sessions() {
        List<NativeSessionItemVo> list = new ArrayList<>();
        for (RtpSession s : platform.sessionManager().sessions()) {
            NativeSessionItemVo vo = new NativeSessionItemVo();
            vo.setChannelId(s.getChannelId());
            vo.setType(s.getType().name());
            vo.setState(s.getState());
            vo.setPort(s.getPort());
            vo.setSsrc(s.getSsrc());
            vo.setMediaUri(s.getMediaUri());
            vo.setCallId(s.getCallId());
            list.add(vo);
        }
        return list;
    }

    // ==================== 录像 ====================

    @GetMapping("/recordings")
    public List<NativeRecordingVo> recordings(@RequestParam(required = false) String channel,
                                              @RequestParam(required = false) Long from,
                                              @RequestParam(required = false) Long to) {
        RecordingService service = platform.recordingService();
        List<Recording> list = service.search(channel, from, to);
        List<NativeRecordingVo> out = new ArrayList<>();
        for (Recording r : list) {
            NativeRecordingVo vo = new NativeRecordingVo();
            vo.setRecordId(r.getRecordId());
            vo.setChannelId(r.getChannelId());
            vo.setStatus(r.getStatus());
            vo.setStartTime(r.getStartTime());
            vo.setEndTime(r.getEndTime());
            vo.setDurationSeconds(r.durationSeconds());
            vo.setFileUrl(r.getFileUrl());
            out.add(vo);
        }
        return out;
    }

    @PostMapping("/record/{deviceId}")
    public NativeOkVo startRecord(@PathVariable String deviceId) {
        Recording recording = platform.recordingService().startRecording(deviceId);
        NativeOkVo vo = NativeOkVo.of(true);
        vo.setRecordId(recording.getRecordId());
        vo.setFile(recording.getFilePath());
        return vo;
    }

    @DeleteMapping("/record/{deviceId}")
    public NativeOkVo stopRecord(@PathVariable String deviceId) {
        Recording recording = platform.recordingService().stopRecording(deviceId);
        NativeOkVo vo = NativeOkVo.of(recording != null);
        vo.setRecordId(recording == null ? "" : recording.getRecordId());
        return vo;
    }

    /** 录像点播（拉入 ZLM 转协议）。 */
    @PostMapping("/recordings/{recordId}/play")
    public NativeRecordingPlayVo playRecording(@PathVariable String recordId) {
        Recording recording = platform.recordingService().get(recordId);
        if (recording == null) {
            NativeRecordingPlayVo vo = new NativeRecordingPlayVo();
            vo.setOk(false);
            vo.setMessage("录像不存在");
            return vo;
        }
        PlayUrls urls = platform.recordingService().playUrl(recording);
        NativeRecordingPlayVo vo = new NativeRecordingPlayVo();
        vo.setOk(urls != null);
        vo.setFileUrl(recording.getFileUrl());
        if (urls != null) {
            vo.setUrls(toUrls(urls));
        }
        return vo;
    }

    // ==================== WebRTC ====================

    /** 浏览器 offer → ZLM answer（嵌入式模式浏览器出画面的唯一通路）。 */
    @PostMapping("/webrtc/play")
    public NativeWebrtcVo webrtcPlay(@RequestParam String stream,
                                     @RequestParam(required = false) String peerId,
                                     @RequestBody String offerSdp) throws Exception {
        String peer = peerId == null || peerId.isBlank()
                ? platform.webRtcService().nextPeerId() : peerId;
        String app = props.getApp();
        String channel = stream;
        // 传 "app/stream" 时拆分
        if (stream.contains("/")) {
            String[] parts = stream.split("/", 2);
            app = parts[0];
            channel = parts[1];
        }
        String answer = platform.webRtcService().exchange(peer, app, channel, offerSdp, 5);
        NativeWebrtcVo vo = new NativeWebrtcVo();
        vo.setCode(0);
        vo.setPeerId(peer);
        vo.setSdp(answer);
        vo.setWebrtcUrl(platform.mediaServer().playUrls(channel).getWebrtc());
        return vo;
    }

    /** WebRTC 播放所需参数。 */
    @GetMapping("/webrtc/config")
    public NativeWebrtcConfigVo webrtcConfig() {
        NativeWebrtcConfigVo vo = new NativeWebrtcConfigVo();
        vo.setWebrtcPort(platform.mediaServer() == null
                ? 0 : platform.mediaServer().getRtcPort());
        vo.setApp(props.getApp());
        vo.setNote("无 ICE 服务，浏览器与平台需同一局域网/可直连");
        return vo;
    }

    // ==================== 工具 ====================

    private NativeSessionVo sessionInfo(RtpSession session) {
        NativeSessionVo vo = new NativeSessionVo();
        vo.setOk(true);
        vo.setChannelId(session.getChannelId());
        vo.setType(session.getType().name());
        vo.setCallId(session.getCallId());
        vo.setPort(session.getPort());
        vo.setSsrc(session.getSsrc());
        vo.setState(session.getState());
        vo.setMediaUri(session.getMediaUri());
        PlayUrls urls = platform.mediaService().playUrls(session.getChannelId());
        vo.setUrls(toUrls(urls));
        return vo;
    }

    private static NativePlayUrlsVo toUrls(PlayUrls urls) {
        if (urls == null) {
            return null;
        }
        NativePlayUrlsVo vo = new NativePlayUrlsVo();
        vo.setHttpFlv(urls.getHttpFlv());
        vo.setWsFlv(urls.getWsFlv());
        vo.setHls(urls.getHls());
        vo.setWebrtc(urls.getWebrtc());
        vo.setRtsp(urls.getRtsp());
        vo.setRtmp(urls.getRtmp());
        return vo;
    }
}
