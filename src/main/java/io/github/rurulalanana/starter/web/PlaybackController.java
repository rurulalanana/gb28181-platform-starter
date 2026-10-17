package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.dto.PlaybackStartRequest;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.PlaybackVo;
import io.github.rurulalanana.starter.vo.RecordListVo;
import io.github.rurulalanana.starter.vo.RecordVo;
import io.github.rurulalanana.starter.vo.StreamListVo;
import io.github.rurulalanana.starter.vo.StreamStopVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 前端设备录像接口。
 *
 * <pre>
 * GET  /api/v1/playback/recordlist    查询录像列表
 * POST /api/v1/playback/start         开始回放 / 下载
 * POST /api/v1/playback/stop          停止回放
 * POST /api/v1/playback/control       回放控制（播放/暂停/跳转/倍速）
 * GET  /api/v1/playback/streamlist    回放流列表
 * GET  /api/v1/playback/streaminfo    单条回放流信息（含下载进度）
 * </pre>
 *
 * <p>回放与直播的关键差异：回放流<b>多路不复用</b>，同一通道每次回放都会占用设备一路
 * 输出，因此务必及时调用 {@code playback/stop}，否则设备会过载。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final RecordService recordService;
    private final StreamService streamService;

    /**
     * 查询录像列表。
     *
     * <p>走标准 9.8 的 {@code RecordInfo} 查询，同步等待设备应答；
     * 结果同时缓存到 {@code gb_device_record} 便于后续分页与按天统计。
     */
    @GetMapping("/recordlist")
    public ApiResponse<RecordListVo> recordList(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "config") String center,
            @RequestParam(defaultValue = "config") String indistinct,
            @RequestParam(required = false) Integer streamnumber,
            @RequestParam(defaultValue = "15") Integer timeout) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        String start = starttime;
        String end = endtime == null || endtime.isBlank()
                ? TimeFormats.toIso(LocalDateTime.now()) : endtime;
        List<RecordItem> items = recordService.queryDeviceRecords(channelId, start, end, timeout);
        List<RecordVo> list = new ArrayList<>();
        for (RecordItem item : items) {
            list.add(recordView(item, channelId));
        }
        // 设备不应答时回退到本地缓存（上一次查询结果）
        if (list.isEmpty()) {
            List<GbDeviceRecord> cached = recordService.deviceRecords(channelId,
                    TimeFormats.parse(start), TimeFormats.parse(end), type);
            for (GbDeviceRecord r : cached) {
                RecordVo vo = new RecordVo();
                vo.setDeviceId(r.getChannelId());
                vo.setName(r.getName());
                vo.setFilePath(r.getFilePath());
                vo.setAddress(r.getAddress());
                vo.setStartTime(TimeFormats.toIso(r.getStartTime()));
                vo.setEndTime(TimeFormats.toIso(r.getEndTime()));
                vo.setFileSize(r.getSize());
                vo.setType(r.getType());
                list.add(vo);
            }
            if (!list.isEmpty()) {
                log.info("设备未应答，返回本地缓存的 {} 条录像记录: channel={}", list.size(), channelId);
            }
        }
        RecordListVo vo = new RecordListVo();
        vo.setDeviceId(channelId);
        vo.setSumNum(list.size());
        vo.setRecordList(list);
        return ApiResponse.ok(vo);
    }

    /** 开始回放 / 下载。 */
    @PostMapping("/start")
    public ApiResponse<PlaybackVo> start(@RequestBody PlaybackStartRequest req) {
        StpUtil.checkLogin();
        // serial = 设备编码（SIP 目标），code = 通道编码（省略则视为设备直连）
        String deviceId = req.getSerial();
        if (deviceId == null || deviceId.isBlank()) {
            throw BizException.badRequest("缺少参数 serial（设备编码）");
        }
        String channelId = req.resolveCode();
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少参数 serial 或 code");
        }
        if (req.getFilePath() == null || req.getFilePath().isBlank()) {
            if (req.getStarttime() == null || req.getStarttime().isBlank()) {
                throw BizException.badRequest("缺少 filePath，且未提供 starttime 用于定位录像");
            }
            // 未给 filePath 时从缓存里挑一条覆盖 starttime 的录像
            List<GbDeviceRecord> records = recordService.deviceRecords(channelId,
                    TimeFormats.parse(req.getStarttime()),
                    TimeFormats.parse(req.getEndtime() == null
                            ? req.getStarttime() : req.getEndtime()), null);
            if (records.isEmpty()) {
                throw BizException.badRequest(
                        "未找到匹配的录像文件，请先调用 playback/recordlist 获取 FilePath");
            }
            req.setFilePath(records.get(0).getFilePath());
        }
        RecordItem item = RecordItem.ofFilePath(channelId, req.getFilePath());
        GbStream stream = streamService.startPlayback(deviceId, channelId, item);
        StreamVo base = streamService.describe(streamService.session(deviceId, channelId));
        PlaybackVo vo = new PlaybackVo();
        BeanUtils.copyProperties(base, vo);
        if (vo.getStreamId() == null) {
            vo.setStreamId(stream.getStreamKey());
            vo.setWebrtc(stream.getWebrtc());
            vo.setFlv(stream.getHttpFlv());
            vo.setHls(stream.getHls());
        }
        vo.setPlaybackStartTime(req.getStarttime());
        vo.setPlaybackEndTime(req.getEndtime());
        vo.setPlaybackSpeed(1);
        vo.setPlaybackPaused(false);
        vo.setDownload(Boolean.TRUE.equals(req.getDownload()));
        return ApiResponse.ok(vo);
    }

    /** 停止回放。 */
    @PostMapping("/stop")
    public ApiResponse<StreamStopVo> stop(@RequestParam(required = false) String streamid,
                                          @RequestParam(required = false) String serial,
                                          @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        RtpSession session = resolveSession(streamid, serial, code);
        if (session == null) {
            throw BizException.badRequest("回放会话不存在，或需提供 streamid 或 serial/code 以定位");
        }
        // 下载模式下文件已由 ZLM 落盘，停止前先取出路径
        String mediaUri = session.getMediaUri();
        String channelId = session.getChannelId();
        // 必须按类型停：回放与直播是独立会话，用 stop(device, channel) 只会去关直播
        boolean stopped = streamService.stop(session.getDeviceId(), channelId,
                io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        StreamStopVo vo = new StreamStopVo();
        vo.setOk(stopped);
        vo.setChannelId(channelId);
        if (mediaUri != null) {
            vo.setFilePath(mediaUri);
        }
        return ApiResponse.ok(vo);
    }

    /**
     * 回放控制。
     *
     * <p>命令映射到标准附录 D 的 {@code u=} 取值：
     * {@code play} / {@code pause} / {@code teardown} / {@code scale}。
     */
    @PostMapping("/control")
    public ApiResponse<Void> control(@RequestParam(required = false) String streamid,
                                     @RequestParam(required = false) String serial,
                                     @RequestParam(required = false) String code,
                                     @RequestParam String command,
                                     @RequestParam(required = false) String range,
                                     @RequestParam(defaultValue = "2") Float scale) {
        StpUtil.checkLogin();
        RtpSession session = resolveSession(streamid, serial, code);
        if (session == null) {
            throw BizException.badRequest("需提供 streamid 或 serial/code 以定位回放流");
        }
        if (session.getCallId() == null) {
            throw BizException.badRequest("回放会话不存在，请先调用 playback/start");
        }
        Gb28181MediaService.PlaybackCommand cmd;
        java.util.Date seekTime = null;
        float rate = scale == null ? 1f : scale;
        switch (command == null ? "" : command.trim().toLowerCase()) {
            case "play" -> {
                cmd = Gb28181MediaService.PlaybackCommand.PLAY;
                // range=now 表示从当前位置继续；否则按相对秒数跳转
                if (range != null && !range.isBlank() && !"now".equalsIgnoreCase(range)) {
                    try {
                        long offset = Long.parseLong(range.trim());
                        seekTime = new java.util.Date(System.currentTimeMillis() + offset * 1000);
                        cmd = Gb28181MediaService.PlaybackCommand.SEEK;
                    } catch (NumberFormatException e) {
                        throw BizException.badRequest("range 应为秒数或 now：" + range);
                    }
                }
            }
            case "pause" -> cmd = Gb28181MediaService.PlaybackCommand.PAUSE;
            case "scale" -> cmd = Gb28181MediaService.PlaybackCommand.SPEED;
            case "teardown" -> {
                streamService.stop(session.getDeviceId(), session.getChannelId());
                return ApiResponse.ok();
            }
            default -> throw BizException.badRequest("不支持的回放控制命令：" + command);
        }
        // 回放控制是发给设备的信令，目标必须是设备编码（不是通道编码）
        streamService.mediaService().playbackControl(session.getDeviceId(), session.getCallId(),
                cmd, seekTime, rate);
        return ApiResponse.ok();
    }

    /** 回放流列表。 */
    @GetMapping("/streamlist")
    public ApiResponse<StreamListVo> streamList(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        List<StreamVo> all = streamService.playbackList();
        List<StreamVo> filtered = new ArrayList<>();
        for (StreamVo s : all) {
            if (serial != null && !serial.isBlank() && !serial.equals(s.getDeviceId())) {
                continue;
            }
            if (code != null && !code.isBlank() && !code.equals(s.getChannelId())) {
                continue;
            }
            filtered.add(s);
        }
        StreamListVo vo = new StreamListVo();
        vo.setStreamCount(filtered.size());
        vo.setStreams(PageQuery.slice(filtered, start, limit));
        return ApiResponse.ok(vo);
    }

    /** 单条回放流信息。 */
    @GetMapping("/streaminfo")
    public ApiResponse<PlaybackVo> streamInfo(@RequestParam String streamid) {
        StpUtil.checkLogin();
        RtpSession session = findSession(streamid);
        if (session == null) {
            throw BizException.notFound("回放流不存在：" + streamid);
        }
        StreamVo base = streamService.describe(session);
        PlaybackVo vo = new PlaybackVo();
        BeanUtils.copyProperties(base, vo);
        vo.setTimestampSec(Math.max(0,
                (System.currentTimeMillis() - session.getCreatedAt()) / 1000));
        vo.setPlaybackProgress(0);
        vo.setDownloadProgress(0);
        vo.setPlaybackPaused(false);
        return ApiResponse.ok(vo);
    }

    // ==================== 内部 ====================

    /**
     * 由 streamid 或 serial/code 定位回放会话。
     *
     * <p>会话对象同时带设备编码与通道编码，故直接返回会话而非通道编码——
     * 通道编码跨设备可重复，光有通道编码无法唯一定位（会误停别的设备的流）。
     */
    private RtpSession resolveSession(String streamid, String serial, String code) {
        if (streamid != null && !streamid.isBlank()) {
            return findSession(streamid);
        }
        String channel = (code == null || code.isBlank()) ? serial : code;
        if (channel == null || channel.isBlank()) {
            return null;
        }
        // serial 是设备编码：能同时命中 (设备, 通道) 时最准
        RtpSession session = (serial == null || serial.isBlank())
                ? null : streamService.session(serial, channel);
        return session != null ? session : streamService.session(channel);
    }

    /** 由 streamid 或 serial/code 定位通道编号（仅用于展示）。 */
    private String resolveChannel(String streamid, String serial, String code) {
        RtpSession session = resolveSession(streamid, serial, code);
        if (session != null) {
            return session.getChannelId();
        }
        if (streamid != null && !streamid.isBlank()) {
            return channelOf(streamid);
        }
        return (code == null || code.isBlank()) ? serial : code;
    }

    /**
     * 按流标识查会话。
     *
     * <p>支持三种形式：复合标识 {@code <设备编码>/<通道编码>}、
     * 裸通道编码（设备直连）、以及流名。
     */
    private RtpSession findSession(String streamid) {
        if (streamid == null || streamid.isBlank()) {
            return null;
        }
        // 复合标识优先：直接按会话键命中
        RtpSession session = streamService.sessionByKey(streamid);
        if (session != null) {
            return session;
        }
        // 裸通道编码（设备直连）
        session = streamService.session(streamid);
        if (session != null) {
            return session;
        }
        for (RtpSession s : streamService.sessions()) {
            if (streamid.equals(s.getStream())) {
                return s;
            }
        }
        return null;
    }

    /**
     * 从复合标识 {@code <设备编码>/<通道编码>} 中取出通道编码。
     *
     * <p>不按斜杠盲拆：GB28181 编码本身不含斜杠，而历史流名曾形如
     * {@code <通道编码>/<序号>}，故只在第二段是 20 位编码时才当作复合标识。
     */
    private static String channelOf(String stream) {
        int slash = stream.lastIndexOf('/');
        if (slash <= 0) {
            return stream;
        }
        String tail = stream.substring(slash + 1);
        return ZlmMediaServer.isChannelId(tail) ? tail : stream;
    }

    /** 录像段 → 返回体。 */
    private RecordVo recordView(RecordItem item, String channelId) {
        RecordVo vo = new RecordVo();
        vo.setDeviceId(item.getDeviceId() == null ? channelId : item.getDeviceId());
        vo.setName(item.getName());
        vo.setFilePath(item.getFilePath());
        vo.setAddress(item.getAddress());
        vo.setStartTime(TimeFormats.toIso(TimeFormats.of(item.getStartTime())));
        vo.setEndTime(TimeFormats.toIso(TimeFormats.of(item.getEndTime())));
        vo.setFileSize(item.getFileSize());
        vo.setType(item.getType());
        vo.setSecrecy(0);
        return vo;
    }
}
