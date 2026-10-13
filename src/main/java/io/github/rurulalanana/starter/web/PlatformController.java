package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.simulator.DeviceSimulatorService;
import io.github.rurulalanana.starter.simulator.SimulatedDevice;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 REST API：覆盖设备、点播、回放、下载、录像、WebRTC、模拟器。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlatformController {

    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;
    private final DeviceSimulatorService simulator;

    // ==================== 状态 ====================

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("platformId", props.getPlatformId());
        map.put("sip", props.getSipHost() + ":" + props.getSipPort());
        map.put("mediaIp", platform.mediaServer() == null ? "" : props.getMediaIp());
        if (platform.mediaServer() != null) {
            map.put("http", platform.mediaServer().getHttpPort());
            map.put("rtmp", platform.mediaServer().getRtmpPort());
            map.put("rtsp", platform.mediaServer().getRtspPort());
            map.put("webrtc", platform.mediaServer().getRtcPort());
        }
        map.put("sessionCount", platform.sessionManager() == null ? 0 : platform.sessionManager().sessionCount());
        map.put("recordCount", platform.recordingService() == null ? 0 : platform.recordingService().all().size());
        map.put("simulatorCount", simulator.list().size());
        map.put("simulatorPushMedia", props.getSimulator().isPushTestMedia());
        map.put("note", "嵌入式模式下 ZLM 的 HTTP 播放路由未注册，浏览器出画面请用 WebRTC");
        return map;
    }

    /** 平台注册表中的在线设备。 */
    @GetMapping("/devices")
    public List<Map<String, Object>> devices() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (platform.sipServer() != null && platform.sipServer().getRegistry() != null) {
            platform.sipServer().getRegistry().listAll().forEach(session -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deviceId", session.getDeviceId());
                m.put("address", session.getAddress());
                m.put("online", platform.sipServer().getRegistry().isOnline(session.getDeviceId()));
                m.put("registerTime", session.getRegisterTime());
                list.add(m);
            });
        }
        return list;
    }

    // ==================== 媒体会话 ====================

    /** 平台主动点播（标准 9.2）。 */
    @PostMapping("/play/{deviceId}")
    public Map<String, Object> play(@PathVariable String deviceId) {
        RtpSession session = platform.mediaService().play(deviceId);
        return sessionInfo(session);
    }

    /** 检索设备录像（标准 9.8），返回录像段列表。 */
    @GetMapping("/records/{deviceId}")
    public List<Map<String, Object>> queryRecords(@PathVariable String deviceId,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) throws Exception {
        String start = from == null ? "2000-01-01T00:00:00" : from;
        String end = to == null ? "2099-12-31T23:59:59" : to;
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        platform.mediaService().queryRecordInfo(deviceId, start, end, items -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (RecordItem item : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deviceId", item.getDeviceId());
                m.put("name", item.getName());
                m.put("filePath", item.getFilePath());
                m.put("type", item.getType());
                m.put("startTime", RecordItem.formatTime(item.getStartTime()));
                m.put("endTime", RecordItem.formatTime(item.getEndTime()));
                m.put("fileSize", item.getFileSize());
                out.add(m);
            }
            future.complete(out);
        });
        // 模拟设备/不响应设备超时返回空列表
        List<Map<String, Object>> result = future.get(3, TimeUnit.SECONDS);
        return result;
    }

    /** 发起历史回放（标准 9.8）：filePath 取自检索结果。 */
    @PostMapping("/playback/{deviceId}")
    public Map<String, Object> playback(@PathVariable String deviceId, @RequestParam String filePath) {
        RecordItem item = RecordItem.ofFilePath(deviceId, filePath);
        RtpSession session = platform.mediaService().playback(deviceId, item);
        return sessionInfo(session);
    }

    /** 回放控制（标准附录 D）。 */
    @PostMapping("/playback/{deviceId}/control")
    public Map<String, Object> playbackControl(@PathVariable String deviceId,
                                               @RequestParam String callId,
                                               @RequestParam(defaultValue = "pause") String command,
                                               @RequestParam(required = false) Long seek,
                                               @RequestParam(defaultValue = "1.0") float scale) {
        Gb28181MediaService.PlaybackCommand cmd;
        switch (command.toLowerCase()) {
            case "play": cmd = Gb28181MediaService.PlaybackCommand.PLAY; break;
            case "pause": cmd = Gb28181MediaService.PlaybackCommand.PAUSE; break;
            case "seek": cmd = Gb28181MediaService.PlaybackCommand.SEEK; break;
            case "speed": cmd = Gb28181MediaService.PlaybackCommand.SPEED; break;
            default: cmd = Gb28181MediaService.PlaybackCommand.STOP; break;
        }
        platform.mediaService().playbackControl(deviceId, callId, cmd,
                seek == null ? null : new java.util.Date(seek), scale);
        return Map.of("ok", true, "command", command);
    }

    /** 文件下载（标准 9.9）。 */
    @PostMapping("/download/{deviceId}")
    public Map<String, Object> download(@PathVariable String deviceId, @RequestParam String filePath) {
        RtpSession session = platform.mediaService().download(deviceId, filePath);
        return sessionInfo(session);
    }

    /** 停止点播并释放收流端口。 */
    @PostMapping("/stop/{deviceId}")
    public Map<String, Object> stop(@PathVariable String deviceId) {
        platform.mediaService().stop(deviceId);
        return Map.of("ok", true, "deviceId", deviceId);
    }

    /** 当前会话 + 播放地址。 */
    @GetMapping("/stream/{deviceId}")
    public Map<String, Object> stream(@PathVariable String deviceId) {
        RtpSession session = platform.sessionManager().getByChannel(deviceId);
        if (session == null) {
            return Map.of("streaming", false, "deviceId", deviceId);
        }
        return sessionInfo(session);
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RtpSession s : platform.sessionManager().sessions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channelId", s.getChannelId());
            m.put("type", s.getType().name());
            m.put("state", s.getState());
            m.put("port", s.getPort());
            m.put("ssrc", s.getSsrc());
            m.put("mediaUri", s.getMediaUri());
            m.put("callId", s.getCallId());
            list.add(m);
        }
        return list;
    }

    // ==================== 录像 ====================

    @GetMapping("/recordings")
    public List<Map<String, Object>> recordings(@RequestParam(required = false) String channel,
                                                @RequestParam(required = false) Long from,
                                                @RequestParam(required = false) Long to) {
        RecordingService service = platform.recordingService();
        List<Recording> list = service.search(channel, from, to);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Recording r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", r.getRecordId());
            m.put("channelId", r.getChannelId());
            m.put("status", r.getStatus());
            m.put("startTime", r.getStartTime());
            m.put("endTime", r.getEndTime());
            m.put("durationSeconds", r.durationSeconds());
            m.put("fileUrl", r.getFileUrl());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/record/{deviceId}")
    public Map<String, Object> startRecord(@PathVariable String deviceId) {
        Recording recording = platform.recordingService().startRecording(deviceId);
        return Map.of("ok", true, "recordId", recording.getRecordId(), "file", recording.getFilePath());
    }

    @DeleteMapping("/record/{deviceId}")
    public Map<String, Object> stopRecord(@PathVariable String deviceId) {
        Recording recording = platform.recordingService().stopRecording(deviceId);
        return Map.of("ok", recording != null, "recordId", recording == null ? "" : recording.getRecordId());
    }

    /** 录像点播（拉入 ZLM 转协议）。 */
    @PostMapping("/recordings/{recordId}/play")
    public Map<String, Object> playRecording(@PathVariable String recordId) {
        Recording recording = platform.recordingService().get(recordId);
        if (recording == null) {
            return Map.of("ok", false, "message", "录像不存在");
        }
        PlayUrls urls = platform.recordingService().playUrl(recording);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", urls != null);
        m.put("fileUrl", recording.getFileUrl());
        if (urls != null) {
            m.put("urls", urls);
        }
        return m;
    }

    // ==================== WebRTC ====================

    /** 浏览器 offer → ZLM answer（嵌入式模式浏览器出画面的唯一通路）。 */
    @PostMapping("/webrtc/play")
    public Map<String, Object> webrtcPlay(@RequestParam String stream,
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
        Map<String, Object> m = new HashMap<>();
        m.put("code", 0);
        m.put("peerId", peer);
        m.put("sdp", answer);
        m.put("webrtcUrl", platform.mediaServer().playUrls(channel).getWebrtc());
        return m;
    }

    /** WebRTC 播放所需参数。 */
    @GetMapping("/webrtc/config")
    public Map<String, Object> webrtcConfig() {
        Map<String, Object> m = new HashMap<>();
        m.put("webrtcPort", platform.mediaServer() == null ? 0 : platform.mediaServer().getRtcPort());
        m.put("app", props.getApp());
        m.put("note", "无 ICE 服务，浏览器与平台需同一局域网/可直连");
        return m;
    }

    // ==================== 模拟器 ====================

    @GetMapping("/simulators")
    public List<Map<String, Object>> simulators() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SimulatedDevice d : simulator.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("name", d.getName());
            m.put("address", d.getAddress());
            m.put("registered", d.isRegistered());
            m.put("pushing", d.isPushing());
            m.put("lastRequest", d.getLastRequest());
            m.put("receivedRequests", d.getReceivedRequests());
            list.add(m);
        }
        return list;
    }

    @PostMapping("/simulators")
    public Map<String, Object> addSimulator(@RequestParam String deviceId,
                                            @RequestParam(required = false) String name) throws Exception {
        SimulatedDevice device = simulator.addDevice(deviceId,
                name == null ? "模拟通道 " + deviceId : name, props.getSimulator().isPushTestMedia());
        return Map.of("ok", true, "deviceId", device.getDeviceId(), "address", device.getAddress());
    }

    @DeleteMapping("/simulators/{deviceId}")
    public Map<String, Object> removeSimulator(@PathVariable String deviceId) {
        simulator.removeDevice(deviceId);
        return Map.of("ok", true);
    }

    /** 开关某模拟设备的 ffmpeg 测试推流。 */
    @PostMapping("/simulators/{deviceId}/media")
    public Map<String, Object> toggleMedia(@PathVariable String deviceId,
                                           @RequestParam(defaultValue = "true") boolean enabled) {
        simulator.toggleMedia(deviceId, enabled);
        return Map.of("ok", true, "enabled", enabled);
    }

    // ==================== 工具 ====================

    private Map<String, Object> sessionInfo(RtpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("channelId", session.getChannelId());
        m.put("type", session.getType().name());
        m.put("callId", session.getCallId());
        m.put("port", session.getPort());
        m.put("ssrc", session.getSsrc());
        m.put("state", session.getState());
        PlayUrls urls = platform.mediaService().playUrls(session.getChannelId());
        m.put("urls", urls);
        return m;
    }
}