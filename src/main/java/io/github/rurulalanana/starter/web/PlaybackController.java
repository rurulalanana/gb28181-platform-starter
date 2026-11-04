package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.MediaType;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.dto.PlaybackStartRequest;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.entity.GbStream;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.DownloadService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.PlaybackDownloadVo;
import io.github.rurulalanana.starter.vo.PlaybackVo;
import io.github.rurulalanana.starter.vo.RecordListVo;
import io.github.rurulalanana.starter.vo.RecordVo;
import io.github.rurulalanana.starter.vo.StreamListVo;
import io.github.rurulalanana.starter.vo.StreamStopVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 前端设备录像接口。路由：
 * <pre>
 * GET  /api/v1/playback/recordlist    查询录像列表
 * POST /api/v1/playback/start         开始回放 / 下载
 * POST /api/v1/playback/stop          停止回放
 * POST /api/v1/playback/control       回放控制（播放/暂停/跳转/倍速）
 * GET  /api/v1/playback/streamlist    回放流列表
 * GET  /api/v1/playback/streaminfo    单条回放流信息（含下载进度）
 * </pre>
 *
 * <p>回放流不复用：同一通道每次回放都占用设备一路输出，必须及时调用 {@code playback/stop}。
 */
@Slf4j
@RestController
@Tag(name = "设备录像回放", description = "录像检索、回放点播、下载")
@RequestMapping("/api/v1/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final RecordService recordService;
    private final StreamService streamService;
    /** 通道编码反查设备编码：SIP 寻址只认设备编码。 */
    private final DeviceService deviceService;
    private final DownloadService downloadService;
    /** 录像文件路径 → 签名静态 URL 的唯一出口。 */
    private final io.github.rurulalanana.starter.config.SpaWebConfiguration spaWebConfiguration;

    /** 走标准 9.8 的 {@code RecordInfo} 查询（同步等设备应答），结果缓存到 {@code gb_device_record}。 */
    @Operation(summary = "查询录像列表")
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
            // 默认 20 秒而非 15：设备自己等 15 秒后才回 400 wait for recordinfo timeout[15s]，
            // 取 15 会与设备的回话点赛跑，丢掉它给出的原因。
            @RequestParam(defaultValue = "20") Integer timeout) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        // serial 是设备编码（SIP 只认注册实体），channelId 是通道编码；录像挂在通道上，两者不能混用。
        String deviceId = code == null || code.isBlank()
                ? deviceService.deviceIdOfChannel(channelId) : serial;
        String start = starttime;
        String end = endtime == null || endtime.isBlank()
                ? TimeFormats.toIso(LocalDateTime.now()) : endtime;
        // 带应答标志的检索：设备没答 ≠ 没有录像，必须区分。
        RecordService.DeviceRecordQuery result = recordService.queryDeviceRecordsAnswered(
                deviceId == null ? channelId : deviceId, channelId, start, end, timeout);
        List<RecordVo> list = new ArrayList<>();
        for (RecordItem item : result.items()) {
            list.add(recordView(item, channelId));
        }
        boolean fromCache = false;
        if (!result.deviceAnswered()) {
            // 仅设备未应答时降级到缓存。设备明确回「没有录像」时不能用缓存顶替，
            // 否则会显示已失效的旧录像，点回放才失败。
            List<GbDeviceRecord> cached = recordService.deviceRecords(channelId,
                    TimeFormats.parse(start), TimeFormats.parse(end), type);
            for (GbDeviceRecord r : cached) {
                RecordVo row = new RecordVo();
                row.setDeviceId(r.getChannelId());
                row.setName(r.getName());
                row.setFilePath(r.getFilePath());
                row.setAddress(r.getAddress());
                row.setStartTime(TimeFormats.toIso(r.getStartTime()));
                row.setEndTime(TimeFormats.toIso(r.getEndTime()));
                row.setFileSize(r.getSize());
                row.setType(r.getType());
                list.add(row);
            }
            fromCache = !list.isEmpty();
        }
        RecordListVo vo = new RecordListVo();
        vo.setDeviceId(channelId);
        vo.setSumNum(list.size());
        vo.setRecordList(list);
        vo.setDeviceAnswered(result.deviceAnswered());
        vo.setFromCache(fromCache);
        if (!result.deviceAnswered()) {
            // 带上设备原话：例如 LiveGBS 回 400 wait for recordinfo timeout[15s]，
            // 只报「设备未应答」会把排查引向平台/网络。
            String base = fromCache
                    ? "设备未应答本次检索，下面是上一次查询的缓存结果（可能不完整）"
                    : "设备未应答本次录像检索";
            vo.setNotice(result.failure() != null
                    ? base + "：" + result.failure()
                    // 连 SIP 响应都没有（UDP 丢包或设备未处理）：
                    // 与「回了 400 但说明原因」区分，前者先查网络/设备负载。
                    : base + "（未收到设备的 SIP 应答）。请确认设备在线，或稍后重试");
            log.warn("设备录像检索未应答: channel={} 缓存条数={} 设备响应={}",
                    channelId, list.size(), result.failure());
        }
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "开始回放 / 下载")
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
            LocalDateTime wantStart = TimeFormats.parse(req.getStarttime());
            LocalDateTime wantEnd = TimeFormats.parse(req.getEndtime() == null
                    ? req.getStarttime() : req.getEndtime());
            // 未给 filePath 时从缓存里挑覆盖该起点的一段
            List<GbDeviceRecord> records = recordService.deviceRecords(channelId,
                    wantStart, wantEnd, null);
            if (records.isEmpty()) {
                throw BizException.badRequest(
                        "未找到匹配的录像文件，请先调用 playback/recordlist 获取 FilePath");
            }
            GbDeviceRecord hit = pickRecord(records, wantStart);
            boolean wholeSegment = isWholeSegment(hit, wantStart, wantEnd);
            // 只有设备真的给了 FilePath 才填（回放 INVITE 的 u= 用它）。实测 LiveGBS、多数国产
            // 摄像机按时间段管理录像，不填 FilePath；强行塞 Name/路径会让设备按「文件不存在」拒绝。
            // 请求段内一小截时不填：u= 一旦给文件路径，设备就播整个文件并忽略 t=，
            // 「只要 14:00~14:05」会变成「下整个 2 小时」，此时改走 u=<通道>:0 + t= 定位。
            req.setFilePath(wholeSegment && hit.getFilePath() != null
                    ? hit.getFilePath() : "");
            // 必须保留用户请求的区间：早先直接 setStarttime(hit.getStartTime()) 覆盖成整段，
            // 使「只下载 14:00~14:05」永远变成整段（画质无损时下载耗时由内容时长决定）。
            // 这里只把区间收拢到匹配段边界内，结束时间缺失时补成该段结束。
            LocalDateTime start = wantStart;
            LocalDateTime end = req.getEndtime() == null ? null : wantEnd;
            if (hit.getStartTime() != null && start.isBefore(hit.getStartTime())) {
                start = hit.getStartTime();
            }
            if (end == null || (hit.getEndTime() != null && end.isAfter(hit.getEndTime()))) {
                end = hit.getEndTime() == null ? end : hit.getEndTime();
            }
            req.setStarttime(TimeFormats.toIso(start));
            if (end != null) {
                req.setEndtime(TimeFormats.toIso(end));
            }
        }
        // 有 FilePath 按文件回放，否则按时间区间回放（FilePath 在标准里可选）。
        RecordItem item = req.getFilePath() == null || req.getFilePath().isBlank()
                ? RecordItem.ofRange(channelId,
                        TimeFormats.toDate(TimeFormats.parse(req.getStarttime())),
                        TimeFormats.toDate(TimeFormats.parse(req.getEndtime() == null
                                ? req.getStarttime() : req.getEndtime())), null)
                : RecordItem.ofFilePath(channelId, req.getFilePath());
        // 倍速只在下载时生效：普通回放必须 1 倍速，否则点开看到的是快进画面。
        int downloadSpeed = Boolean.TRUE.equals(req.getDownload())
                ? Math.max(1, req.getDownloadSpeed() == null ? 1 : req.getDownloadSpeed())
                : 1;
        GbStream stream = streamService.startPlayback(deviceId, channelId, item, downloadSpeed);
        // 必须按回放会话取地址：session(deviceId, channelId) 查的是直播键（无类型后缀），
        // 云端录像保活使通道长期有直播流，会返回直播地址（OSD 显示当下时间）。
        // 回放键是 <设备>/<通道>_playback，正是 GbStream#getStreamKey() 保存的值。
        RtpSession playbackSession = streamService.sessionByKey(stream.getStreamKey());
        PlaybackVo vo = new PlaybackVo();
        if (playbackSession != null) {
            BeanUtils.copyProperties(streamService.describe(playbackSession), vo);
        }
        // describe 失败时退回入库信息，至少保证地址非空
        if (vo.getStreamId() == null) {
            vo.setStreamId(stream.getStreamKey());
            vo.setWebrtc(stream.getWebrtc());
            vo.setFlv(stream.getHttpFlv());
            vo.setHls(stream.getHls());
        }
        vo.setPlaybackStartTime(req.getStarttime());
        vo.setPlaybackEndTime(req.getEndtime());
        vo.setPlaybackSpeed(downloadSpeed);
        vo.setPlaybackPaused(false);
        vo.setDownload(Boolean.TRUE.equals(req.getDownload()));
        return ApiResponse.ok(vo);
    }

    /**
     * 停止回放。下载中的通道不会被停掉（后台下载依赖这路流）；
     * 前端关弹窗、切页面都会调本接口，照常停流会中断下载，此时返回 {@code ok=false}。
     */
    @Operation(summary = "停止回放")
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
        StreamStopVo vo = new StreamStopVo();
        vo.setChannelId(channelId);
        if (mediaUri != null) {
            vo.setFilePath(mediaUri);
        }
        if (downloadService.isDownloading(session.getDeviceId(), channelId)) {
            log.info("回放通道正在后台下载，跳过停流: device={} channel={}",
                    session.getDeviceId(), channelId);
            vo.setOk(false);
            vo.setMessage("该通道正在后台下载，完成后自动释放");
            return ApiResponse.ok(vo);
        }
        // 必须按类型停：回放与直播是独立会话，用 stop(device, channel) 只会去关直播
        boolean stopped = streamService.stop(session.getDeviceId(), channelId,
                io.github.rurulalanana.gb28181.sip.zlm.MediaType.PLAYBACK);
        vo.setOk(stopped);
        return ApiResponse.ok(vo);
    }

    /** 命令映射到标准附录 D 的 {@code u=} 取值：play / pause / teardown / scale。 */
    @Operation(summary = "回放控制")
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
        long seekSecond = 0L;
        float rate = scale == null ? 1f : scale;
        switch (command == null ? "" : command.trim().toLowerCase()) {
            case "pause" -> cmd = Gb28181MediaService.PlaybackCommand.PAUSE;
            case "play", "resume" -> {
                // range 是相对本段录像起点的秒数，不是绝对时间：国标 npt 是段内偏移，
                // 传绝对秒数会跳到「开播 N 年后」立即结束。传 now/不传 = 继续播放。
                if (range != null && !range.isBlank() && !"now".equalsIgnoreCase(range)) {
                    try {
                        seekSecond = Math.max(0L, Long.parseLong(range.trim()));
                    } catch (NumberFormatException e) {
                        throw BizException.badRequest("range 应为相对秒数或 now：" + range);
                    }
                    cmd = Gb28181MediaService.PlaybackCommand.SEEK;
                } else {
                    cmd = Gb28181MediaService.PlaybackCommand.RESUME;
                }
            }
            case "seek" -> {
                if (range == null || range.isBlank()) {
                    throw BizException.badRequest("seek 需要 range（相对秒数）");
                }
                try {
                    seekSecond = Math.max(0L, Long.parseLong(range.trim()));
                } catch (NumberFormatException e) {
                    throw BizException.badRequest("range 应为相对秒数：" + range);
                }
                cmd = Gb28181MediaService.PlaybackCommand.SEEK;
            }
            case "scale", "speed" -> cmd = Gb28181MediaService.PlaybackCommand.SPEED;
            case "teardown" -> {
                streamService.stop(session.getDeviceId(), session.getChannelId());
                return ApiResponse.ok();
            }
            default -> throw BizException.badRequest("不支持的回放控制命令：" + command);
        }
        // 回放控制是发给设备的信令，目标必须是设备编码（不是通道编码）
        streamService.mediaService().playbackControl(session.getDeviceId(), session.getCallId(),
                cmd, seekSecond, rate);
        return ApiResponse.ok();
    }

    // ==================== 录像下载 ====================

    /**
     * 开始下载：把当前回放流录成本地 MP4（后台任务）。必须先调 {@code playback/start}，
     * 边看边下共用一路流，避免重复 INVITE 占带宽；任务不随页面消失。
     *
     * @param duration      录像段总时长（秒），{@code 0} 表示未知
     * @param expectedBytes 设备侧文件大小，是「内容已录完」的主判据：
     *                      设备不会按请求的结束时间停推，只能靠大小判断；{@code 0} 表示未知
     */
    @Operation(summary = "开始下载：对当前回放流开始录制成本地 MP4（后台任务）")
    @PostMapping("/download/start")
    public ApiResponse<PlaybackDownloadVo.Progress> downloadStart(
            @RequestParam String streamid,
            @RequestParam(defaultValue = "0") long duration,
            @RequestParam(defaultValue = "1") int speed,
            @RequestParam(defaultValue = "0") long expectedBytes,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime) {
        StpUtil.checkLogin();
        RtpSession session = resolveSession(streamid, null, null);
        DownloadService.View task = downloadService.start(new DownloadService.Request(
                streamid,
                session == null ? null : session.getDeviceId(),
                session == null ? null : session.getChannelId(),
                name, starttime, endtime, duration, speed, expectedBytes));
        PlaybackDownloadVo.Progress vo = new PlaybackDownloadVo.Progress();
        vo.setStreamId(task.taskId());
        vo.setRecordedSecond(task.recordedSecond());
        vo.setTotalSecond(task.totalSecond());
        vo.setPercent(task.percent());
        vo.setFinished(task.finished());
        vo.setFileSize(task.fileSize());
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "下载进度")
    @GetMapping("/download/progress")
    public ApiResponse<PlaybackDownloadVo.Progress> downloadProgress(@RequestParam String streamid) {
        StpUtil.checkLogin();
        var p = downloadService.progress(streamid);
        PlaybackDownloadVo.Progress vo = new PlaybackDownloadVo.Progress();
        vo.setStreamId(streamid);
        vo.setRecordedSecond(p.recordedSecond());
        vo.setTotalSecond(p.totalSecond());
        vo.setFinished(p.finished());
        vo.setFileSize(p.fileSize());
        vo.setPercent(p.totalSecond() > 0
                ? (int) Math.min(100, p.recordedSecond() * 100 / p.totalSecond()) : 0);
        return ApiResponse.ok(vo);
    }

    /** 全局下载任务列表：任意页面都能拉到，切页/刷新后仍可取进度与文件。 */
    @Operation(summary = "全局下载任务列表（进行中 + 最近已完成）")
    @GetMapping("/download/list")
    public ApiResponse<List<PlaybackDownloadVo.Task>> downloadList() {
        StpUtil.checkLogin();
        List<PlaybackDownloadVo.Task> out = new ArrayList<>();
        for (DownloadService.View v : downloadService.list()) {
            PlaybackDownloadVo.Task vo = new PlaybackDownloadVo.Task();
            vo.setTaskId(v.taskId());
            vo.setDeviceId(v.deviceId());
            vo.setChannelId(v.channelId());
            vo.setChannelName(v.channelName() == null || v.channelName().isBlank()
                    ? v.channelId() : v.channelName());
            vo.setStartAt(v.startAt());
            vo.setEndAt(v.endAt());
            vo.setRecordedSecond(v.recordedSecond());
            vo.setTotalSecond(v.totalSecond());
            vo.setPercent(v.percent());
            vo.setSpeed(v.speed());
            vo.setFinished(v.finished());
            vo.setOk(v.ok());
            vo.setFileName(v.fileName());
            vo.setFileSize(v.fileSize());
            // 完成任务把磁盘路径换成带签名的静态地址，前端直接交给浏览器下载
            vo.setUrl(v.filePath() == null || v.filePath().isBlank()
                    ? v.url() : spaWebConfiguration.toStaticUrl(v.filePath()));
            vo.setMessage(v.message());
            vo.setElapsedSecond(v.elapsedSecond());
            out.add(vo);
        }
        return ApiResponse.ok(out);
    }

    @Operation(summary = "从任务列表移除一条已完成/已取消的记录（不删磁盘文件）")
    @PostMapping("/download/remove")
    public ApiResponse<Void> downloadRemove(@RequestParam String taskid) {
        StpUtil.checkLogin();
        downloadService.remove(taskid);
        return ApiResponse.ok();
    }

    /** 结束下载，返回可下载的 MP4 地址：走 {@code /record/...} 静态路径
     *  （nginx sendfile + 签名鉴权），支持 Range 断点续传。 */
    @Operation(summary = "结束下载，返回可下载的 MP4 地址")
    @PostMapping("/download/finish")
    public ApiResponse<PlaybackDownloadVo.Finish> downloadFinish(@RequestParam String streamid) {
        StpUtil.checkLogin();
        PlaybackDownloadVo.Finish vo = new PlaybackDownloadVo.Finish();
        // 已被后台自动收尾：直接把结果还给前端
        for (DownloadService.View v : downloadService.list()) {
            if (!streamid.equals(v.taskId()) || !v.finished()) {
                continue;
            }
            vo.setOk(v.ok());
            vo.setMessage(v.message());
            vo.setFileName(v.fileName());
            vo.setFileSize(v.fileSize());
            vo.setFilePath(v.filePath());
            vo.setUrl(v.url());
            return ApiResponse.ok(vo);
        }
        String path = downloadService.finish(streamid);
        if (path == null) {
            vo.setOk(false);
            vo.setMessage("录制未产出文件，请确认录像时段内设备确实有数据");
            return ApiResponse.ok(vo);
        }
        java.io.File file = new java.io.File(path);
        vo.setOk(true);
        vo.setFilePath(path);
        vo.setFileName(file.getName());
        vo.setFileSize(file.length());
        // 走「路径 → 签名静态 URL」的统一出口，避免漏签导致 403
        vo.setUrl(spaWebConfiguration.toStaticUrl(path));
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "取消下载")
    @PostMapping("/download/cancel")
    public ApiResponse<Void> downloadCancel(@RequestParam String streamid) {
        StpUtil.checkLogin();
        downloadService.cancel(streamid);
        return ApiResponse.ok();
    }

    @Operation(summary = "回放流列表")
    @GetMapping("/streamlist")
    public ApiResponse<StreamListVo> streamList(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        return ApiResponse.ok(StreamService.filterStreams(
                streamService.playbackList(), start, limit, serial, code, null));
    }

    @Operation(summary = "单条回放流信息")
    @GetMapping("/streaminfo")
    public ApiResponse<PlaybackVo> streamInfo(@RequestParam String streamid) {
        StpUtil.checkLogin();
        RtpSession session = streamService.findSession(streamid);
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
     * 由 streamid 或 serial/code 定位<b>回放</b>会话。返回会话而非通道编码：通道编码跨设备可重复。
     * 必须按 {@link MediaType#PLAYBACK} 查——同一通道通常还有一路直播（云端录像保活），
     * 键分别是 {@code 设备/通道} 与 {@code 设备/通道_playback}。早先用直播键解析，
     * 拖动/暂停/倍速的 SIP INFO 全发到直播对话，设备回 200 却不执行（拖进度条无反应、倍速无效）。
     */
    private RtpSession resolveSession(String streamid, String serial, String code) {
        if (streamid != null && !streamid.isBlank()) {
            RtpSession byKey = streamService.findSession(streamid);
            if (byKey != null) {
                return byKey;
            }
        }
        String channel = DeviceRef.channelOf(serial, code);
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return streamService.playbackSession(serial == null || serial.isBlank() ? null : serial, channel);
    }

    /**
     * 从候选录像段里挑出包含请求起点的那段。不能用 {@code get(0)}：deviceRecords 按起点降序返回，
     * 交集查询会把相邻段带进来（请求 00:00~02:00 时 02:00-04:00 也满足），第一条就是下一段，
     * 偏移量正好等于一段长度，易误判成时区问题。
     * 优先级：起点完全相同 → 覆盖起点且起点最晚 → 起点最早。
     */
    private static GbDeviceRecord pickRecord(List<GbDeviceRecord> records,
                                             LocalDateTime wantStart) {
        if (wantStart == null) {
            return records.get(0);
        }
        GbDeviceRecord covering = null;
        for (GbDeviceRecord r : records) {
            LocalDateTime s = r.getStartTime();
            if (s == null) {
                continue;
            }
            if (s.isEqual(wantStart)) {
                return r;
            }
            LocalDateTime e = r.getEndTime();
            boolean inRange = e == null
                    ? !s.isAfter(wantStart)
                    : (!s.isAfter(wantStart) && !e.isBefore(wantStart));
            if (inRange && (covering == null || s.isAfter(covering.getStartTime()))) {
                covering = r;
            }
        }
        if (covering != null) {
            return covering;
        }
        // 兜底：起点最早的一段
        GbDeviceRecord earliest = records.get(0);
        for (GbDeviceRecord r : records) {
            if (r.getStartTime() != null && earliest.getStartTime() != null
                    && r.getStartTime().isBefore(earliest.getStartTime())) {
                earliest = r;
            }
        }
        return earliest;
    }

    /**
     * 请求区间是否就是这段录像本身（允许 2 秒误差）。只有整段才传 {@code FilePath}：
     * 按文件回放对设备最友好，但粒度是整个文件，段内一小截会变成下整段。
     */
    private static boolean isWholeSegment(GbDeviceRecord hit,
                                          LocalDateTime start, LocalDateTime end) {
        if (hit.getStartTime() == null || start == null || end == null) {
            return false;
        }
        long startDiff = Math.abs(java.time.Duration.between(
                hit.getStartTime(), start).getSeconds());
        long endDiff = hit.getEndTime() == null
                ? 0 : Math.abs(java.time.Duration.between(hit.getEndTime(), end).getSeconds());
        if (startDiff > 2 || endDiff > 2) {
            return false;
        }
        // 区间长度也要基本一致，否则说明请求只覆盖了段内一部分
        long segSeconds = hit.getEndTime() == null ? 0
                : java.time.Duration.between(hit.getStartTime(), hit.getEndTime()).getSeconds();
        long wantSeconds = java.time.Duration.between(start, end).getSeconds();
        return segSeconds <= 0 || wantSeconds >= segSeconds * 99 / 100;
    }

    private String resolveChannel(String streamid, String serial, String code) {
        RtpSession session = resolveSession(streamid, serial, code);
        if (session != null) {
            return session.getChannelId();
        }
        if (streamid != null && !streamid.isBlank()) {
            return channelOf(streamid);
        }
        return DeviceRef.channelOf(serial, code);
    }

    /**
     * 从 {@code <设备编码>/<通道编码>} 取出通道编码。不盲拆斜杠：GB28181 编码不含斜杠，
     * 历史流名曾形如 {@code <通道编码>/<序号>}，只有第二段是 20 位编码时才算复合标识。
     */
    private static String channelOf(String stream) {
        int slash = stream.lastIndexOf('/');
        if (slash <= 0) {
            return stream;
        }
        String tail = stream.substring(slash + 1);
        return ZlmMediaServer.isChannelId(tail) ? tail : stream;
    }

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
