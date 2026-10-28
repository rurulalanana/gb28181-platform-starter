package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.dto.StreamStartRequest;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.RelayService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.RelayListVo;
import io.github.rurulalanana.starter.vo.RelayStartVo;
import io.github.rurulalanana.starter.vo.RelayStopVo;
import io.github.rurulalanana.starter.vo.RelayVo;
import io.github.rurulalanana.starter.vo.StreamListVo;
import io.github.rurulalanana.starter.vo.StreamStopVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时直播接口。
 *
 * <pre>
 * POST /api/v1/stream/start          开始直播
 * POST /api/v1/stream/stop           停止直播
 * GET  /api/v1/stream/list           直播流列表
 * GET  /api/v1/stream/info           单条直播流信息
 * POST /api/v1/stream/relay/start    转推
 * GET  /api/v1/stream/relay/list     转推列表
 * POST /api/v1/stream/relay/stop     停止转推
 * POST /api/v1/stream/osd            视频水印
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamController {

    private final StreamService streamService;
    private final RecordService recordService;
    private final RelayService relayService;
    private final Gb28181MediaPlatform platform;
    private final Gb28181Properties props;

    /**
     * 开始直播。
     *
     * <p>{@code serial} = 设备编码，{@code code} = 通道编码（省略则视为设备直连，
     * 即设备编码本身就是通道编码）。
     */
    @PostMapping("/start")
    public ApiResponse<StreamVo> start(@RequestBody StreamStartRequest req) {
        StpUtil.checkLogin();
        requireMedia();
        String deviceId = requireDevice(req.getSerial());
        String channelId = channelOf(deviceId, req.getCode());
        GbStream stream = streamService.startLive(deviceId, channelId, req.getStreamnumber());
        StreamVo vo = streamService.describe(streamService.session(deviceId, channelId));
        if (vo.getStreamId() == null) {
            vo = describeStream(stream);
        }
        vo.setStreamId(stream.getStreamKey());
        return ApiResponse.ok(vo);
    }

    /** 停止直播。 */
    @PostMapping("/stop")
    public ApiResponse<StreamStopVo> stop(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam(value = "check_outputs", defaultValue = "false")
                                          Boolean checkOutputs) {
        StpUtil.checkLogin();
        String deviceId = requireDevice(serial);
        String channelId = channelOf(deviceId, code);
        if (Boolean.TRUE.equals(checkOutputs)) {
            RtpSession session = streamService.session(deviceId, channelId);
            if (session != null) {
                log.info("停止直播前检查：通道 {} 当前有观看者时仍会停止（嵌入式模式无观看人数统计）", channelId);
            }
        }
        boolean stopped = streamService.stop(deviceId, channelId);
        // 通道下线后转推无源，一并释放（转推按通道聚合，属业务维度）
        int relays = relayService.stopAll(channelId);
        StreamStopVo vo = new StreamStopVo();
        vo.setOk(stopped);
        vo.setChannelId(channelId);
        vo.setRelaysStopped(relays);
        return ApiResponse.ok(vo);
    }

    /** 直播流列表。 */
    @GetMapping("/list")
    public ApiResponse<StreamListVo> list(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        List<StreamVo> all = streamService.liveList();
        List<StreamVo> filtered = new ArrayList<>();
        for (StreamVo s : all) {
            String channelId = s.getChannelId();
            if (serial != null && !serial.isBlank() && !serial.equals(s.getDeviceId())) {
                continue;
            }
            if (code != null && !code.isBlank() && !code.equals(channelId)) {
                continue;
            }
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase();
                String name = s.getChannelName() == null ? "" : s.getChannelName();
                if (!channelId.toLowerCase().contains(needle) && !name.toLowerCase().contains(needle)) {
                    continue;
                }
            }
            filtered.add(s);
        }
        StreamListVo vo = new StreamListVo();
        vo.setStreamCount(filtered.size());
        vo.setStreams(limit > 0 ? PageQuery.slice(filtered, start, limit) : filtered);
        return ApiResponse.ok(vo);
    }

    /** 单条直播流信息。 */
    @GetMapping("/info")
    public ApiResponse<StreamVo> info(@RequestParam String streamid,
                                      @RequestParam(defaultValue = "false") Boolean touch) {
        StpUtil.checkLogin();
        RtpSession session = findSessionByStreamId(streamid);
        if (session == null) {
            throw BizException.notFound("直播流不存在：" + streamid);
        }
        return ApiResponse.ok(streamService.describe(session));
    }

    // ==================== 转推 ====================

    /**
     * 直播转推。
     *
     * <p>用 ZLMediaKit 推流器把本地流推到外部 RTMP/RTSP 地址。
     */
    @PostMapping("/relay/start")
    public ApiResponse<RelayStartVo> relayStart(@RequestParam String serial,
                                                @RequestParam(required = false) String code,
                                                @RequestParam String cdn) {
        StpUtil.checkLogin();
        requireMedia();
        String channelId = channelOf(requireDevice(serial), code);
        RelayVo entry = relayService.start(channelId, cdn);
        RelayStartVo vo = new RelayStartVo();
        vo.setStreamId(entry.getStreamId());
        vo.setRelayCount(relayService.count(channelId));
        vo.setRelayList(relayService.list(channelId));
        return ApiResponse.ok(vo);
    }

    /** 转推列表。 */
    @GetMapping("/relay/list")
    public ApiResponse<RelayListVo> relayList(@RequestParam String serial,
                                              @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = channelOf(requireDevice(serial), code);
        List<RelayVo> list = relayService.list(channelId);
        RelayListVo vo = new RelayListVo();
        vo.setRelayCount(list.size());
        vo.setRelayList(list);
        return ApiResponse.ok(vo);
    }

    /** 停止转推。 */
    @PostMapping("/relay/stop")
    public ApiResponse<RelayStopVo> relayStop(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestParam(required = false) String cdn) {
        StpUtil.checkLogin();
        String channelId = channelOf(requireDevice(serial), code);
        int removed = (cdn == null || cdn.isBlank())
                ? relayService.stopAll(channelId)
                : (relayService.stop(channelId, cdn) ? 1 : 0);
        List<RelayVo> list = relayService.list(channelId);
        RelayStopVo vo = new RelayStopVo();
        vo.setRemoved(removed);
        vo.setRelayCount(list.size());
        vo.setRelayList(list);
        return ApiResponse.ok(vo);
    }

    /**
     * 视频水印。
     *
     * <p>嵌入式模式未注册 {@code /index/api/addFFmpegSource}，无法动态加水印；
     * 需在独立部署的 MediaServer 上配置 {@code [ffmpeg]} 转码实现。
     */
    @PostMapping("/osd")
    public ApiResponse<Void> osd(@RequestParam String serial,
                                 @RequestParam String code,
                                 @RequestParam(required = false) String streamid,
                                 @RequestParam(required = false) String text,
                                 @RequestParam(defaultValue = "white") String color,
                                 @RequestParam(value = "border_color", defaultValue = "black") String borderColor,
                                 @RequestParam(required = false) String x,
                                 @RequestParam(required = false) String y,
                                 @RequestParam(required = false) Integer size) {
        StpUtil.checkLogin();
        throw BizException.badRequest(
                "嵌入式 ZLMediaKit 不支持动态水印；请改为独立部署 MediaServer 并配置 [ffmpeg] 转码");
    }

    // ==================== 内部 ====================

    /** 解析并校验设备编码。 */
    private String requireDevice(String serial) {
        if (serial == null || serial.isBlank()) {
            throw BizException.badRequest("缺少参数 serial（设备编码）");
        }
        return serial;
    }

    /** 通道编码：未指定 code 时视为设备直连（设备编码即通道编码）。 */
    private String channelOf(String deviceId, String code) {
        return code == null || code.isBlank() ? deviceId : code;
    }

    /** 校验媒体服务可用。 */
    private void requireMedia() {
        if (streamService.mediaService() == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
    }

    /**
     * 按会话键 / ZLM 流名 / 裸通道编码查会话。
     *
     * <p><b>三种都要认</b>：{@code /stream/start} 与 {@code /stream/list} 返回给
     * 前端的 {@code streamId} 是<b>会话键</b> {@code <设备编码>/<通道编码>}，
     * 调用方自然会把同一个值传给 {@code /stream/info}。原来这里只按
     * 「通道编码」和「ZLM 流名」查，于是用接口自己吐出来的 streamId 回查
     * 恒 404（实测 {@code 直播流不存在：3402…/3402…}），前后端对不上。
     */
    private RtpSession findSessionByStreamId(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return null;
        }
        RtpSession session = streamService.sessionByKey(streamId);
        if (session != null) {
            return session;
        }
        session = streamService.session(streamId);
        if (session != null) {
            return session;
        }
        for (RtpSession s : streamService.sessions()) {
            if (streamId.equals(s.getStream())) {
                return s;
            }
        }
        return null;
    }

    /** 从库记录还原返回体（会话已不在内存时使用）。 */
    private StreamVo describeStream(GbStream stream) {
        StreamVo vo = new StreamVo();
        vo.setStreamId(stream.getStreamKey());
        vo.setDeviceId(stream.getDeviceId());
        vo.setChannelId(stream.getChannelId());
        vo.setSmsId(props.getPlatformId());
        vo.setSsrc(stream.getSsrc());
        vo.setRtpPort(stream.getRtpPort());
        vo.setState(stream.getState());
        vo.setTransport(stream.getTransport());
        vo.setWebrtc(stream.getWebrtc());
        vo.setFlv(stream.getHttpFlv());
        vo.setWsFlv(stream.getWsFlv());
        vo.setHls(stream.getHls());
        vo.setRtsp(stream.getRtsp());
        vo.setRtmp(stream.getRtmp());
        vo.setStartAt(TimeFormats.toDisplay(stream.getStartTime()));
        return vo;
    }
}
