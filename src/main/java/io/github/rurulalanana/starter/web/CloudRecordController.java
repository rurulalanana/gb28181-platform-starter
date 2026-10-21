package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.ShareTokenService;
import io.github.rurulalanana.starter.service.FfmpegSnapService;
import io.github.rurulalanana.starter.service.SnapCaptureService;
import io.github.rurulalanana.starter.service.SnapService;
import io.github.rurulalanana.starter.service.TranscodeService;
import io.github.rurulalanana.starter.storage.ObjectStorageService;
import io.github.rurulalanana.starter.vo.CloudChannelListVo;
import io.github.rurulalanana.starter.vo.CloudChannelVo;
import io.github.rurulalanana.starter.vo.CloudDailyVo;
import io.github.rurulalanana.starter.vo.CloudFlagsVo;
import io.github.rurulalanana.starter.vo.CloudMonthVo;
import io.github.rurulalanana.starter.vo.CloudPlayVo;
import io.github.rurulalanana.starter.vo.CloudRecordVo;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.DownloadVo;import io.github.rurulalanana.starter.vo.SnapVo;import io.github.rurulalanana.starter.vo.VideoRangeVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端服务器录像接口。
 *
 * <p>「云端录像」指平台用 ZLMediaKit 把收到的实时流录成 MP4，
 * 文件保存在平台磁盘（{@code gb28181.record-root}），与设备侧录像无关。
 * 启用对象存储（{@code gb28181.oss.enabled=true}）时，停止录制后自动归档到
 * 对象存储，下载接口返回签名/外链地址。
 *
 * <pre>
 * GET  /api/v1/cloudrecord/querychannels   有录像的通道
 * GET  /api/v1/cloudrecord/queryflags      按月统计有录像的日子
 * GET  /api/v1/cloudrecord/querymonthly    按月查询
 * GET  /api/v1/cloudrecord/querydaily      按日查询
 * POST /api/v1/cloudrecord/play            录像点播（拉入 ZLM 转协议）
 * POST /api/v1/cloudrecord/stopplay        停止录像点播
 * POST /api/v1/cloudrecord/remove          删除单条
 * POST /api/v1/cloudrecord/removechannel   删除通道全部
 * POST /api/v1/cloudrecord/removedaily     按天删除
 * POST /api/v1/cloudrecord/setimportant    重要标记
 * GET  /api/v1/cloudrecord/download/...    下载录像文件
 * GET  /api/v1/cloudrecord/file/{recordId} 本地录像文件流
 * GET  /api/v1/cloudrecord/video/...       指定时间段合成播放/下载
 * POST /api/v1/cloudrecord/snap/grab       服务端抓拍（拉流 + ffmpeg 解码，唯一来源）
 * GET  /api/v1/cloudrecord/snap/...        读取通道快照（免登录）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cloudrecord")
@RequiredArgsConstructor
public class CloudRecordController {

    private final RecordService recordService;
    private final DeviceService deviceService;
    private final SnapService snapService;
    private final SnapCaptureService snapCaptureService;
    private final ShareTokenService shareTokenService;
    private final TranscodeService transcodeService;

    /** 有云端录像的通道。 */
    @GetMapping("/querychannels")
    public ApiResponse<CloudChannelListVo> queryChannels(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q) {
        StpUtil.checkLogin();
        List<CloudChannelVo> rows = new ArrayList<>();
        for (var channel : deviceService.allChannels()) {
            List<GbRecord> records = recordService.cloudRecords(channel.getChannelId(), null, null);
            if (records.isEmpty()) {
                continue;
            }
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase();
                String name = channel.displayName() == null ? "" : channel.displayName().toLowerCase();
                if (!channel.getChannelId().toLowerCase().contains(needle)
                        && !name.contains(needle)) {
                    continue;
                }
            }
            var device = deviceService.findDevice(channel.getDeviceId());
            CloudChannelVo row = new CloudChannelVo();
            row.setSerial(channel.getDeviceId());
            row.setCode(channel.getChannelId());
            row.setName(channel.getName());
            row.setCustomName(channel.getCustomName());
            row.setStatus(channel.getStatus());
            row.setDeviceName(device == null ? null : device.getName());
            row.setDeviceCustomName(device == null ? null : device.getCustomName());
            row.setDeviceOnline(device != null && Boolean.TRUE.equals(device.getOnline()));
            row.setUpdatedAt(TimeFormats.toDisplay(channel.getUpdateTime()));
            row.setCreatedAt(TimeFormats.toDisplay(channel.getCreateTime()));
            // 快照：已抓到过才给地址，没抓到时为 null（前端显示占位图）。
            // 快照由前端抓帧后回传，故此处只是「有没有」的探测。
            if (snapService.exists(channel.getDeviceId(), channel.getChannelId())) {
                row.setSnapUrl("/api/v1/cloudrecord/snap/" + channel.getDeviceId()
                        + "/" + channel.getChannelId() + ".jpg");
            }
            rows.add(row);
        }
        CloudChannelListVo vo = new CloudChannelListVo();
        vo.setTotal(rows.size());
        vo.setRows(limit > 0 ? slice(rows, start, limit) : rows);
        return ApiResponse.ok(vo);
    }

    /** 按通道统计有录像的月份。 */
    @GetMapping("/queryflags")
    public ApiResponse<CloudFlagsVo> queryFlags(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "12") Integer interrupt,
            @RequestParam(required = false) Integer latest) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        List<GbRecord> records = recordService.cloudRecords(channelId, null, null);
        // key=YYYYMM, value=当月每天是否有录像的 0/1 串
        Map<String, String> m = new LinkedHashMap<>();
        for (GbRecord r : records) {
            if (r.getStartTime() == null) {
                continue;
            }
            String month = TimeFormats.MONTH.format(r.getStartTime());
            m.computeIfAbsent(month, k -> recordService.monthFlags(channelId, k));
        }
        CloudFlagsVo vo = new CloudFlagsVo();
        vo.setFlags(m);
        return ApiResponse.ok(vo);
    }

    /** 按月查询。 */
    @GetMapping("/querymonthly")
    public ApiResponse<CloudMonthVo> queryMonthly(@RequestParam String serial,
                                                  @RequestParam(required = false) String code,
                                                  @RequestParam String period) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        CloudMonthVo vo = new CloudMonthVo();
        vo.setFlag(recordService.monthFlags(channelId, period));
        return ApiResponse.ok(vo);
    }

    /** 按日查询。 */
    @GetMapping("/querydaily")
    public ApiResponse<CloudDailyVo> queryDaily(@RequestParam String serial,
                                                @RequestParam(required = false) String code,
                                                @RequestParam String period) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        LocalDateTime day = TimeFormats.parse(period);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + period);
        }
        List<GbRecord> records = recordService.cloudRecords(channelId, day, day.plusDays(1));
        List<CloudRecordVo> list = new ArrayList<>();
        for (GbRecord r : records) {
            list.add(toItem(r, false));
        }
        var channel = deviceService.findChannel(channelId);
        CloudDailyVo vo = new CloudDailyVo();
        vo.setName(channel == null ? channelId : channel.displayName());
        vo.setList(list);
        return ApiResponse.ok(vo);
    }

    /** 删除单条录像（仅管理员：录像文件不可恢复）。 */
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String serial,
                                    @RequestParam(required = false) String code,
                                    @RequestParam String period) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        String channelId = code == null || code.isBlank() ? serial : code;
        GbRecord record = recordService.findByPeriod(channelId, period);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + period);
        }
        recordService.deleteFile(record);
        recordService.removeRecord(record.getRecordId());
        return ApiResponse.ok();
    }

    /** 删除通道所有录像（仅管理员）。 */
    @PostMapping("/removechannel")
    public ApiResponse<DeletedVo> removeChannel(@RequestParam String serial,
                                                @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        String channelId = code == null || code.isBlank() ? serial : code;
        int deleted = 0;
        for (GbRecord r : recordService.cloudRecords(channelId, null, null)) {
            recordService.deleteFile(r);
            deleted++;
        }
        recordService.removeChannelRecords(channelId);
        DeletedVo vo = DeletedVo.of(deleted);
        vo.setChannelId(channelId);
        return ApiResponse.ok(vo);
    }

    /** 按天删除（仅管理员）。 */
    @PostMapping("/removedaily")
    public ApiResponse<DeletedVo> removeDaily(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestParam String period) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        String channelId = code == null || code.isBlank() ? serial : code;
        LocalDateTime day = TimeFormats.parse(period);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + period);
        }
        for (GbRecord r : recordService.cloudRecords(channelId, day, day.plusDays(1))) {
            recordService.deleteFile(r);
        }
        int deleted = recordService.removeDailyRecords(channelId, period);
        DeletedVo vo = DeletedVo.of(deleted);
        vo.setChannelId(channelId);
        return ApiResponse.ok(vo);
    }

    /** 重要标记。 */
    @PostMapping("/setimportant")
    public ApiResponse<Void> setImportant(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam String period,
                                          @RequestParam Boolean important) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        GbRecord record = recordService.findByPeriod(channelId, period);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + period);
        }
        recordService.setImportant(record.getRecordId(), important);
        return ApiResponse.ok();
    }

    /** 录像点播：把 MP4 拉入 ZLM 转协议输出（返回 WebRTC/FLV/HLS 等播放地址）。 */
    @PostMapping("/play")
    public ApiResponse<CloudPlayVo> play(@RequestParam String recordId) {
        StpUtil.checkLogin();
        GbRecord record = recordService.findRecord(recordId);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + recordId);
        }
        return ApiResponse.ok(playVo(record, recordId));
    }

    /**
     * 组装点播响应体。
     *
     * <p><b>为什么要单独抽出来</b>：分享页（{@code /share/{token}}）是<b>免登录</b>接口，
     * 但它也要返回播放地址。若直接调用 {@link #play(String)}，
     * 那个方法第一行是 {@code StpUtil.checkLogin()} —— 免登录访客必然 401
     * （实测踩过：令牌校验都过了，却在拼播放地址时抛「未登录」）。
     * 故把「查记录 + 起播 + 拼地址」拆到这里，由登录接口与免登录接口各自调用。
     *
     * @param record   录像记录
     * @param recordId 录像 ID
     * @return 播放地址
     */
    private CloudPlayVo playVo(GbRecord record, String recordId) {
        PlayUrls urls = recordService.playCloudRecord(recordId);
        CloudPlayVo vo = new CloudPlayVo();
        vo.setRecordId(recordId);
        vo.setChannelId(record.getChannelId());
        vo.setFileName(record.getFileName());
        vo.setStartAt(TimeFormats.toCompact(record.getStartTime()));
        vo.setDuration(record.getDuration());
        vo.setFileSize(record.getFileSize());
        vo.setWebrtc(urls.getWebrtc());
        vo.setFlv(urls.getHttpFlv());
        vo.setWsFlv(urls.getWsFlv());
        vo.setHls(urls.getHls());
        vo.setRtsp(urls.getRtsp());
        vo.setRtmp(urls.getRtmp());
        vo.setDownloadUrl(recordService.downloadUrl(record));
        vo.setNote("嵌入式 ZLMediaKit 下浏览器出画面请用 webrtc 地址");
        return vo;
    }

    /** 停止录像点播。 */
    @PostMapping("/stopplay")
    public ApiResponse<Void> stopPlay(@RequestParam String recordId) {
        StpUtil.checkLogin();
        recordService.stopCloudRecordPlay(recordId);
        return ApiResponse.ok();
    }

    /** 下载录像文件。 */
    @GetMapping("/download/{serial}/{code}/{period}")
    public ApiResponse<DownloadVo> download(@PathVariable String serial,
                                            @PathVariable String code,
                                            @PathVariable String period) {
        StpUtil.checkLogin();
        GbRecord record = recordService.findByPeriod(code, period);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + period);
        }
        if (!recordService.fileAvailable(record)) {
            throw BizException.notFound("录像文件已丢失：" + record.getFilePath());
        }
        DownloadVo vo = new DownloadVo();
        vo.setFileName(record.getFileName());
        vo.setFilePath(record.getFilePath());
        vo.setFileSize(record.getFileSize());
        vo.setDownloadUrl(recordService.downloadUrl(record));
        vo.setStorageType(record.getStorageType());
        return ApiResponse.ok(vo);
    }

    /**
     * 录像文件流（供浏览器 {@code <video>} 直接播放）。
     *
     * <p><b>为什么用原生 {@code <video>} 而不是 jessibuca</b>：
     * 实测本机 Chrome/Electron 148 能直接解 H265（{@code canPlayType} 返回
     * probably，实测能出画面 2560x1440），而原生 video 天然支持
     * <b>HTTP Range → 拖动进度条直接定位</b>，不需要服务端做 seek。
     * ZLM 那边反而做不到（MP4 源无索引，{@code mk_media_source_seek_to} 返回 1）。
     *
     * <p><b>三点必要的处理</b>：
     * <ul>
     *   <li>{@code Content-Disposition: inline} —— 默认的 attachment 会让浏览器
     *       直接下载而不是播放；</li>
     *   <li><b>支持 Range</b> —— 拖动进度条时浏览器会发 {@code Range} 请求，
     *       不支持的话每次定位都要重下整个文件；</li>
     *   <li><b>token 走 query</b> —— {@code <video src>} 请求带不了自定义头，
     *       Sa-Token 默认会从 query 参数里读 token（{@code is-read-body}），
     *       前端把 token 拼在 URL 上即可。</li>
     * </ul>
     *
     * <p>对象存储中的录像不经过本接口（用 download 接口返回的签名地址）。
     */
    @GetMapping("/file/{recordId}")
    public void file(@PathVariable String recordId,
                     jakarta.servlet.http.HttpServletRequest request,
                     jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        StpUtil.checkLogin();
        GbRecord record = recordService.findRecord(recordId);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + recordId);
        }
        if (ObjectStorageService.TYPE_OSS.equals(record.getStorageType())) {
            throw BizException.badRequest("该录像已归档到对象存储，请使用 download 接口返回的地址");
        }
        Path path = Path.of(record.getFilePath());
        if (!Files.exists(path)) {
            throw BizException.notFound("录像文件已丢失：" + record.getFilePath());
        }
        /*
         * 优先用「浏览器可播」的转码版（H265 → 720p H264 + faststart）。
         *
         * <p>浏览器（Chrome/Electron）实际解不了 H265 —— canPlayType 虽返回 probably，
         * 播放时报 PIPELINE_ERROR_DECODE。H264 则完全正常，且原生 <video> 支持
         * HTTP Range → 拖动进度条直接定位。
         *
         * <p>转码失败时回退到原文件（宁可让用户看花屏也别看不到）。
         */
        String playable = transcodeService.browserPlayable(recordId, record.getFilePath());
        if (playable != null) {
            path = Path.of(playable);
        }
        long size = Files.size(path);

        response.setContentType("video/mp4");
        // inline：让浏览器就地播放，而不是弹下载
        response.setHeader("Content-Disposition", "inline; filename=\""
                + record.getFileName().replace("\"", "") + "\"");
        // 告诉浏览器支持 Range（拖进度条靠它）
        response.setHeader("Accept-Ranges", "bytes");

        long start = 0;
        long len = size;
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            long[] bounds = parseRange(range.substring(6), size);
            if (bounds != null) {
                start = bounds[0];
                long end = bounds[1];
                len = end - start + 1;
                /*
                 * 从头开始的请求（bytes=0-）直接回 200 + 整个文件，<b>不</b>回 206。
                 *
                 * <p>原因：这些录像 MP4 是「mdat 在前、moov 在尾」的非 faststart 布局，
                 * Chrome 播它时会先拉全量再按需补尾部。实测：
                 * <ul>
                 *   <li>服务端忽略 Range 返回 200 全量 → 浏览器能正常解码；</li>
                 *   <li>服务端回 206 分片 → Chrome 认为首个响应没拿全，
                 *       继续发 {@code bytes=3244032-} 补尾部，
                 *       最终报 {@code PIPELINE_ERROR_DECODE: video decode error}。</li>
                 * </ul>
                 * HTTP 规范允许服务端忽略 Range（RFC 9110 §14.2），
                 * 所以「起点为 0 就回全量」既合规又最稳。
                 *
                 * <p>非零起点的请求仍然回 206 片段 —— 那是拖进度条真正用到的。
                 */
                if (start > 0) {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT);
                    response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                } else {
                    start = 0;
                    len = size;
                }
            }
        }
        response.setContentLengthLong(len);
        writeRange(path, start, len, response.getOutputStream());
    }

    /**
     * 把文件的 {@code [start, start+len)} 区间写到响应流。
     *
     * <p><b>为什么自己写而不用 Resource / ResourceRegion</b>：
     * 本项目里这两个都踩了坑 ——
     * <ul>
     *   <li>{@code InputStreamResource}：流从文件开头读，请求 {@code bytes=3244032-}
     *       会返回<b>文件开头</b>的字节，Chrome 拿到错位数据后 FFmpegDemuxer 报
     *       {@code DEMUXER_ERROR_COULD_NOT_OPEN}，表现为「播不出来」；</li>
     *   <li>{@code ResourceRegion}：没有对应的 converter 接手
     *       （{@code No converter for ResourceRegion} / Jackson 报
     *       {@code Type definition error: ChannelInputStream}）；
     *       {@code StreamingResponseBody} 的 lambda 同样不被识别
     *       （{@code No converter for ...$$Lambda}）。</li>
     * </ul>
     * 直接操作 HttpServletResponse 最稳：字节完全可控，也不把整段读进内存。
     */
    private void writeRange(Path path, long start, long len,
                            java.io.OutputStream out) throws java.io.IOException {
        try (java.io.InputStream in = Files.newInputStream(path)) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = len;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    /**
     * 解析 Range 头（只支持单区间）。
     *
     * @param spec 形如 {@code 0-1023} 或 {@code 1024-}
     * @param size 文件总长
     * @return {start, end}；格式非法或越界返回 {@code null}
     */
    private long[] parseRange(String spec, long size) {
        try {
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String from = spec.substring(0, dash).trim();
            String to = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (from.isEmpty()) {
                // 后缀区间：最后 N 字节
                long suffix = Long.parseLong(to);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? size - 1 : Long.parseLong(to);
            }
            if (start < 0 || start >= size || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, size - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 指定时间段录像播放 / 下载。
     *
     * <p>GBS 会把区间内的多段录像用 ffmpeg 合成一个 MP4。本实现不引入 ffmpeg 依赖，
     * 改为返回区间内的录像段列表，由前端按顺序播放；如需真正的单文件合成，
     * 可在部署环境额外接入 ffmpeg。
     */
    @GetMapping("/video/{operate}/{serial}/{code}/{starttime}/{endtime}/video.mp4")
    public ApiResponse<VideoRangeVo> videoOperate(@PathVariable String operate,
                                                  @PathVariable String serial,
                                                  @PathVariable String code,
                                                  @PathVariable String starttime,
                                                  @PathVariable String endtime) {
        StpUtil.checkLogin();
        LocalDateTime from = TimeFormats.parse(starttime);
        LocalDateTime to = TimeFormats.parse(endtime);
        if (from == null || to == null) {
            throw BizException.badRequest("时间格式应为 YYYYMMDDHHmmss");
        }
        List<GbRecord> records = recordService.cloudRecords(code, from, to);
        List<CloudRecordVo> list = new ArrayList<>();
        long totalBytes = 0;
        for (GbRecord r : records) {
            list.add(toItem(r, true));
            totalBytes += r.getFileSize() == null ? 0 : r.getFileSize();
        }
        VideoRangeVo vo = new VideoRangeVo();
        vo.setOperate(operate);
        vo.setSerial(serial);
        vo.setCode(code);
        vo.setStartTime(starttime);
        vo.setEndTime(endtime);
        vo.setRecordCount(list.size());
        vo.setTotalBytes(totalBytes);
        vo.setRecordList(list);
        vo.setNote("本实现返回区间内的录像段列表；如需单文件合成请接入 ffmpeg");
        return ApiResponse.ok(vo);
    }

    // ==================== 内部 ====================

    /** 把录像实体组装为出参。 */
    private CloudRecordVo toItem(GbRecord r, boolean withDownloadUrl) {
        CloudRecordVo item = new CloudRecordVo();
        item.setRecordId(r.getRecordId());
        item.setName(r.getName());
        item.setChannelId(r.getChannelId());
        item.setStartAt(TimeFormats.toCompact(r.getStartTime()));
        item.setEndTime(TimeFormats.toCompact(r.getEndTime()));
        item.setDuration(r.getDuration());
        item.setFileSize(r.getFileSize());
        item.setImportant(r.getImportant());
        item.setStorageType(r.getStorageType());
        item.setUploadState(r.getUploadState());
        item.setRecording(r.getEndTime() == null);
        if (withDownloadUrl && r.getEndTime() != null && recordService.fileAvailable(r)) {
            item.setDownloadUrl(recordService.downloadUrl(r));
        }
        return item;
    }

    private static <T> List<T> slice(List<T> all, int start, int limit) {
        int from = Math.min(Math.max(0, start), all.size());
        int to = Math.min(from + Math.max(0, limit), all.size());
        return all.subList(from, to);
    }

    // ==================== 通道快照 ====================

    /**
     * 服务端抓拍：拉流 → ffmpeg 解码 → 存盘。
     *
     * <p>这是<b>唯一</b>的快照来源：现场是 H265，只有真正解码才能出图。
     * 服务端用外部 ffmpeg 进程完成（jar 增量 0，理由见 {@link FfmpegSnapService}）。
     *
     * @param serial 设备编码
     * @param code   通道编码
     * @return 快照访问路径
     */
    @PostMapping("/snap/grab")
    public ApiResponse<SnapVo> grabSnap(@RequestParam String serial,
                                         @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String url = snapCaptureService.capture(serial, code);
        SnapVo vo = new SnapVo();
        vo.setSerial(serial);
        vo.setCode(code);
        vo.setUrl(url);
        return ApiResponse.ok(vo);
    }

    /**
     * 读取通道快照。
     *
     * <p><b>不校验登录态</b>：快照是「公开的通道缩略图」，会被直接放进
     * {@code <img src>} 里加载 —— {@code <img>} 请求不带自定义头，
     * 带鉴权就永远加载不出来。故按公开静态资源处理。
     */
    @GetMapping("/snap/{serial}/{code}.jpg")
    public org.springframework.http.ResponseEntity<byte[]> snap(
            @PathVariable String serial, @PathVariable String code) {
        byte[] data = snapService.read(serial, code);
        if (data == null || data.length == 0) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                // 快照是「访问时现抓」的产物，缓存 60 秒后失效，避免看到过期画面
                .header("Cache-Control", "public, max-age=60")
                .body(data);
    }

    // ==================== 分享页 ====================

    /**
     * 生成某段录像的分享链接。
     *
     * <p>对齐 LiveGBS 的「分享页」：详情页点分享 → 拿到一个免登录链接 →
     * 别人直接打开就能看这一段录像。
     */
    @PostMapping("/share")
    public ApiResponse<Map<String, String>> share(@RequestParam String recordId) {
        StpUtil.checkLogin();
        GbRecord record = recordService.findRecord(recordId);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + recordId);
        }
        String token = shareTokenService.issue(recordId);
        if (token == null) {
            throw BizException.badRequest("生成分享链接失败");
        }
        Map<String, String> vo = new LinkedHashMap<>();
        vo.put("token", token);
        vo.put("url", "/share/" + token);
        return ApiResponse.ok(vo);
    }

    /**
     * 按分享令牌取录像信息（<b>免登录</b>）。
     *
     * <p>路径在 {@code SaTokenConfiguration} 的放行名单里 —— 分享链接就是给
     * 不登录的人用的。
     */
    @GetMapping("/share/{token}")
    public ApiResponse<Map<String, Object>> shareInfo(@PathVariable String token) {
        GbRecord record = shareTokenService.resolve(recordService, token);
        if (record == null) {
            throw BizException.notFound("分享链接无效或已过期");
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("recordId", record.getRecordId());
        vo.put("channelId", record.getChannelId());
        vo.put("name", record.getName());
        vo.put("startAt", TimeFormats.toCompact(record.getStartTime()));
        vo.put("duration", record.getDuration());
        vo.put("fileSize", record.getFileSize());
        // 播放地址：与登录态下点播走同一条路径。
        // 注意用 playVo(...) 而不是 play(...)：后者带 checkLogin()，
        // 免登录访客会 401（令牌校验已过，却在拼地址时被鉴权拦下）。
        CloudPlayVo play = playVo(record, record.getRecordId());
        vo.put("webrtc", play.getWebrtc());
        vo.put("flv", play.getFlv());
        vo.put("wsFlv", play.getWsFlv());
        vo.put("hls", play.getHls());
        return ApiResponse.ok(vo);
    }
}
