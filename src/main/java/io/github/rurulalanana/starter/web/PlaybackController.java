package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
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
    /** 用于把通道编码反查成设备编码（SIP 寻址只认设备编码）。 */
    private final DeviceService deviceService;
    /** 设备录像下载：把回放流录成本地 MP4。 */
    private final DownloadService downloadService;
    /** 录像文件路径 → 带签名的静态 URL 的唯一出口。 */
    private final io.github.rurulalanana.starter.config.SpaWebConfiguration spaWebConfiguration;

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
            /*
             * 默认 20 秒，而不是 15。
             *
             * <p>设备（下级平台）自己会向上级摄像机等 15 秒，超时才回
             * `400 wait for recordinfo timeout[15s]`。取 15 就变成和设备的
             * 回话点赛跑，白白丢掉它给出的原因。
             */
            @RequestParam(defaultValue = "20") Integer timeout) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        // serial 是设备编码（SIP 寻址），channelId 是通道编码（<DeviceID>）。
        // 录像挂在通道上，但 SIP 只认注册实体 —— 两者不能混用。
        String deviceId = code == null || code.isBlank()
                ? deviceService.deviceIdOfChannel(channelId) : serial;
        String start = starttime;
        String end = endtime == null || endtime.isBlank()
                ? TimeFormats.toIso(LocalDateTime.now()) : endtime;
        /*
         * 走「带应答标志」的检索：设备没答 ≠ 没有录像，必须区分开。
         */
        RecordService.DeviceRecordQuery result = recordService.queryDeviceRecordsAnswered(
                deviceId == null ? channelId : deviceId, channelId, start, end, timeout);
        List<RecordVo> list = new ArrayList<>();
        for (RecordItem item : result.items()) {
            list.add(recordView(item, channelId));
        }
        boolean fromCache = false;
        if (!result.deviceAnswered()) {
            /*
             * 设备没答时才降级到本地缓存。
             *
             * <p>设备明确回了「没有录像」时<b>不能</b>用缓存顶替 ——
             * 那等于把已经失效的旧录像重新显示出来，用户会以为录像还在，
             * 点回放才失败。
             */
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
            /*
             * 把设备的原话带上。
             *
             * <p>设备在 SIP 层拒绝时（例如 LiveGBS 回
             * {@code 400 wait for recordinfo timeout[15s]}）已经把原因说清楚了，
             * 而只报「设备未应答」会让人去查平台/网络 —— 排查方向完全错。
             */
            String base = fromCache
                    ? "设备未应答本次检索，下面是上一次查询的缓存结果（可能不完整）"
                    : "设备未应答本次录像检索";
            vo.setNotice(result.failure() != null
                    ? base + "：" + result.failure()
                    /*
                     * 设备连 SIP 响应都没给（UDP 丢包或设备完全没处理）。
                     * 明确写出来，和「设备回了 400 但说原因」区分开：
                     * 前者要先查网络/设备负载，后者是设备侧检索故障。
                     */
                    : base + "（未收到设备的 SIP 应答）。请确认设备在线，或稍后重试");
            log.warn("设备录像检索未应答: channel={} 缓存条数={} 设备响应={}",
                    channelId, list.size(), result.failure());
        }
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
            LocalDateTime wantStart = TimeFormats.parse(req.getStarttime());
            LocalDateTime wantEnd = TimeFormats.parse(req.getEndtime() == null
                    ? req.getStarttime() : req.getEndtime());
            // 未给 filePath 时从缓存里挑**覆盖这个起点**的那一段
            List<GbDeviceRecord> records = recordService.deviceRecords(channelId,
                    wantStart, wantEnd, null);
            if (records.isEmpty()) {
                throw BizException.badRequest(
                        "未找到匹配的录像文件，请先调用 playback/recordlist 获取 FilePath");
            }
            GbDeviceRecord hit = pickRecord(records, wantStart);
            boolean wholeSegment = isWholeSegment(hit, wantStart, wantEnd);
            /*
             * 只有设备真的给了 FilePath 才填（回放 INVITE 的 u= 用它）。
             * 大量设备（实测 LiveGBS、多数国产摄像机）不填 FilePath，
             * 而是按时间段管理录像 —— 此时必须留空，让媒体层改用
             * StartTime/EndTime 定位。强行把 Name 或路径塞进 u=
             * 会让设备按「文件不存在」直接拒绝。
             *
             * <p><b>请求的是段内一小截时不填 FilePath</b>：{@code u=} 一旦给了
             * 文件路径，设备就按「播放整个文件」处理，{@code t=} 常被忽略 ——
             * 于是「只要 14:00~14:05」会变成「下整个 2 小时」。这种情况改走
             * 纯时间区间（{@code u=<通道>:0}），由 {@code t=} 定位。
             */
            req.setFilePath(wholeSegment && hit.getFilePath() != null
                    ? hit.getFilePath() : "");
            /*
             * 【必须保留用户请求的区间，不能整段覆盖】
             *
             * <p>改造前这里直接 {@code req.setStarttime(hit.getStartTime())}，
             * 把请求区间换成整段录像的时间 —— 于是「只下载 14:00~14:05」永远
             * 变成「下载 12:00~14:00」，下载时长恒等于整段。这是「想快点下」
             * 怎么都快不起来的根因：画质无损时下载耗时由内容时长决定，
             * 不能收窄区间就只能等满整段。
             *
             * <p>现在只做两件必要的事：
             * <ol>
             *   <li>把区间<b>收拢</b>到匹配段的边界内（请求可能略微越过段首尾）；</li>
             *   <li>结束时间缺失时补成该段结束（否则设备会一直推下去）。</li>
             * </ol>
             */
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
        /*
         * 构造回放项：有 FilePath 走按文件回放，没有则按时间区间回放。
         * 见 RecordItem#ofRange 的说明 —— FilePath 在标准里是可选的。
         */
        RecordItem item = req.getFilePath() == null || req.getFilePath().isBlank()
                ? RecordItem.ofRange(channelId,
                        TimeFormats.toDate(TimeFormats.parse(req.getStarttime())),
                        TimeFormats.toDate(TimeFormats.parse(req.getEndtime() == null
                                ? req.getStarttime() : req.getEndtime())), null)
                : RecordItem.ofFilePath(channelId, req.getFilePath());
        /*
         * 倍速只在「下载」时生效：普通回放必须是 1 倍速，否则用户点开回放
         * 看到的是快进画面。这是 {@code PlaybackStartRequest.downloadSpeed}
         * 默认 4 却必须配合 {@code download=true} 的原因。
         */
        int downloadSpeed = Boolean.TRUE.equals(req.getDownload())
                ? Math.max(1, req.getDownloadSpeed() == null ? 1 : req.getDownloadSpeed())
                : 1;
        GbStream stream = streamService.startPlayback(deviceId, channelId, item, downloadSpeed);
        /*
         * 【必须按回放会话取播放地址】
         *
         * <p>不能用 {@code streamService.session(deviceId, channelId)}：那是
         * <b>直播</b>会话（键不带类型后缀）。云端录像自动开录后，通道上长期
         * 有一路直播流在跑，于是这里会拿到<b>直播流的播放地址</b> ——
         * 用户点「回放 2026-10-25 00:00~02:00」，画面里却是当下的实时画面
         * （OSD 时间显示今天）。
         *
         * <p>回放会话的键是 {@code <设备>/<通道>_playback}（见 RtpSession#getSessionKey），
         * {@code GbStream#getStreamKey()} 保存的正是它。
         */
        RtpSession playbackSession = streamService.sessionByKey(stream.getStreamKey());
        PlaybackVo vo = new PlaybackVo();
        if (playbackSession != null) {
            BeanUtils.copyProperties(streamService.describe(playbackSession), vo);
        }
        // describe 拿不到时退回入库的流信息（至少保证地址不为空）
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
     * 停止回放。
     *
     * <p><b>下载中的通道不会被停掉</b>：后台下载依赖这路回放流。
     * 用户关掉回放弹窗、切换页面时前端都会调本接口，若照常停流，
     * 下载会直接中断（这正是「后台下载」改造前的问题）。
     * 此时返回 {@code ok=false} 并说明原因，由界面提示
     * 「后台下载中，完成后自动释放」。
     */
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
        long seekSecond = 0L;
        float rate = scale == null ? 1f : scale;
        switch (command == null ? "" : command.trim().toLowerCase()) {
            case "pause" -> cmd = Gb28181MediaService.PlaybackCommand.PAUSE;
            case "play", "resume" -> {
                /*
                 * range 的语义是「相对本段录像起点的秒数」，不是绝对时间：
                 * 国标设备的 npt 是段内偏移，传绝对秒数会跳到「开播 N 年后」
                 * 而立刻结束。不传 range（或传 now）= 从当前位置继续。
                 */
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
     * 开始下载：对当前回放流开始录制成本地 MP4（后台任务）。
     *
     * <p>必须先有回放流（先调 {@code playback/start}）—— 这样「边看边下」
     * 共用一路流，不会为下载重复向设备发起 INVITE 而占掉一路带宽。
     *
     * <p><b>任务不随页面消失</b>：录满后由服务端自动收尾，
     * 前端通过 {@code download/list} 在任何页面查看与取文件。
     *
     * @param streamid 回放会话键
     * @param duration 录像段总时长（秒），用于计算进度；{@code 0} 表示未知
     * @param speed    请求的设备推流倍速（多数设备忽略，仅作日志）
     * @param expectedBytes 该段在设备侧的文件大小（录像检索的 FileSize）；
     *                它是「内容已录完」的主判据 —— 设备不会按请求的结束时间停推，
     *                只能靠大小判断。{@code 0} 表示未知
     * @param name     通道名（仅用于任务列表展示）
     */
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

    /** 下载进度。 */
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

    /**
     * 全局下载任务列表（进行中 + 最近已完成）。
     *
     * <p>后台下载的关键接口：任意页面都能拉到全部任务，所以用户切走页面、
     * 甚至刷新浏览器之后依然能看到进度、并取回已完成的文件。
     */
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

    /** 从任务列表移除一条已完成/已取消的记录（不删磁盘文件）。 */
    @PostMapping("/download/remove")
    public ApiResponse<Void> downloadRemove(@RequestParam String taskid) {
        StpUtil.checkLogin();
        downloadService.remove(taskid);
        return ApiResponse.ok();
    }

    /**
     * 结束下载，返回可下载的 MP4 地址。
     *
     * <p>地址走已有的 {@code /record/...} 静态路径（nginx sendfile + 签名鉴权），
     * 前端 {@code <a download>} 即可直接下载，支持 Range 断点续传。
     */
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

    /** 取消下载。 */
    @PostMapping("/download/cancel")
    public ApiResponse<Void> downloadCancel(@RequestParam String streamid) {
        StpUtil.checkLogin();
        downloadService.cancel(streamid);
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
        return ApiResponse.ok(StreamService.filterStreams(
                streamService.playbackList(), start, limit, serial, code, null));
    }

    /** 单条回放流信息。 */
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
     * 由 streamid 或 serial/code 定位回放会话。
     *
     * <p>会话对象同时带设备编码与通道编码，故直接返回会话而非通道编码——
     * 通道编码跨设备可重复，光有通道编码无法唯一定位（会误停别的设备的流）。
     */
    private RtpSession resolveSession(String streamid, String serial, String code) {
        if (streamid != null && !streamid.isBlank()) {
            return streamService.findSession(streamid);
        }
        String channel = DeviceRef.channelOf(serial, code);
        if (channel == null || channel.isBlank()) {
            return null;
        }
        // serial 是设备编码：能同时命中 (设备, 通道) 时最准
        RtpSession session = (serial == null || serial.isBlank())
                ? null : streamService.session(serial, channel);
        return session != null ? session : streamService.session(channel);
    }

    /**
     * 从候选录像段里挑出「包含请求起点」的那一段。
     *
     * <p><b>为什么不能用 {@code records.get(0)}</b>：{@code deviceRecords} 按
     * 起始时间<b>降序</b>返回，而交集查询会把相邻的段也带进来 ——
     * 请求 00:00~02:00 时，00:00-02:00 与 02:00-04:00 都满足交集条件
     * （后者 start=02:00 &le; to=02:00），降序第一条就是 02:00-04:00。
     * 用户点 00:00 的录像，看到的却是从 02:00 开始的画面 —— 偏移量正好
     * 等于一段录像的长度，很容易被误判成时区问题。
     *
     * <p>挑选优先级：
     * <ol>
     *   <li>起点与请求完全相同的（用户点的是段首，最精确）</li>
     *   <li>真正包含请求起点的段里，起点最晚的（区间最贴合）</li>
     *   <li>都没有时退化为起点最早的那段（至少不会跳到很后面）</li>
     * </ol>
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
     * 请求区间是否就是这段录像本身（允许 2 秒误差）。
     *
     * <p>只有「整段」时才把 {@code FilePath} 传给设备：按文件回放对设备最友好，
     * 但粒度是「整个文件」。用户只想取段内一小截时必须放弃它 —— 否则设备
     * 会忽略 {@code t=} 去播整个文件，导致「只要 5 分钟」变成「下 2 小时」。
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

    /** 由 streamid 或 serial/code 定位通道编号（仅用于展示）。 */
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
