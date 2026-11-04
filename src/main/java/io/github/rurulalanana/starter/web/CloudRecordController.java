package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.ShareTokenService;
import io.github.rurulalanana.starter.service.FfmpegSnapService;
import io.github.rurulalanana.starter.service.SnapCaptureService;
import io.github.rurulalanana.starter.service.SnapService;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 云端服务器录像接口。云端录像指平台用 ZLMediaKit 把实时流录成 MP4，存在平台磁盘
 * （{@code gb28181.record-root}），与设备侧录像无关；启用对象存储时停录后自动归档，
 * 下载接口返回签名/外链地址。主要路由：
 * <pre>
 * GET  /api/v1/cloudrecord/querychannels / queryflags / querymonthly / querydaily
 * POST /api/v1/cloudrecord/play / stopplay / remove / removechannel / removedaily / setimportant
 * GET  /api/v1/cloudrecord/download/...  file/{recordId}  video/...
 * POST /api/v1/cloudrecord/snap/grab     服务端抓拍（拉流 + ffmpeg 解码，唯一来源）
 * GET  /api/v1/cloudrecord/snap/...      读取通道快照（免登录）
 * </pre>
 */
@Slf4j
@RestController
@Tag(name = "云端录像", description = "录像检索、点播、下载、分享")
@RequestMapping("/api/v1/cloudrecord")
@RequiredArgsConstructor
public class CloudRecordController {

    private final RecordService recordService;
    private final DeviceService deviceService;
    private final SnapService snapService;
    private final SnapCaptureService snapCaptureService;
    private final ShareTokenService shareTokenService;
    /** 用于把录像文件路径换算成 {@code /record/...} 静态地址。 */
    private final io.github.rurulalanana.starter.config.SpaWebConfiguration spaWebConfiguration;

    @Operation(summary = "有云端录像的通道")
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
            // 已抓到过才给地址，没抓到时为 null（前端显示占位图）
            if (snapService.exists(channel.getDeviceId(), channel.getChannelId())) {
                row.setSnapUrl("/api/v1/cloudrecord/snap/" + channel.getDeviceId()
                        + "/" + channel.getChannelId() + ".jpg");
            }
            rows.add(row);
        }
        CloudChannelListVo vo = new CloudChannelListVo();
        vo.setTotal(rows.size());
        vo.setRows(limit > 0 ? PageQuery.slice(rows, start, limit) : rows);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "按通道统计有录像的月份")
    @GetMapping("/queryflags")
    public ApiResponse<CloudFlagsVo> queryFlags(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "12") Integer interrupt,
            @RequestParam(required = false) Integer latest) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
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

    @Operation(summary = "按月查询")
    @GetMapping("/querymonthly")
    public ApiResponse<CloudMonthVo> queryMonthly(@RequestParam String serial,
                                                  @RequestParam(required = false) String code,
                                                  @RequestParam String period) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        CloudMonthVo vo = new CloudMonthVo();
        vo.setFlag(recordService.monthFlags(channelId, period));
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "按日查询")
    @GetMapping("/querydaily")
    public ApiResponse<CloudDailyVo> queryDaily(@RequestParam String serial,
                                                @RequestParam(required = false) String code,
                                                @RequestParam String period) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        LocalDateTime day = TimeFormats.parse(period);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + period);
        }
        List<GbRecord> records = recordService.cloudRecords(channelId, day, day.plusDays(1));
        List<CloudRecordVo> list = new ArrayList<>();
        for (GbRecord r : records) {
            // 要下载地址：前端下载按钮的判据就是 downloadUrl 非空，传 false 会让所有「下载」变灰。
            // 代价只有一次 Files.exists（几十条记录，微秒级），换来文件真没了就不给下载按钮。
            list.add(toItem(r, true));
        }
        var channel = deviceService.findChannel(channelId);
        CloudDailyVo vo = new CloudDailyVo();
        vo.setName(channel == null ? channelId : channel.displayName());
        vo.setList(list);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "删除单条录像（仅管理员：录像文件不可恢复）")
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String serial,
                                    @RequestParam(required = false) String code,
                                    @RequestParam String period) {
        StpUtil.checkPermission(Permissions.CLOUD_DELETE);
        String channelId = DeviceRef.channelOf(serial, code);
        GbRecord record = recordService.findByPeriod(channelId, period);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + period);
        }
        recordService.deleteFile(record);
        recordService.removeRecord(record.getRecordId());
        return ApiResponse.ok();
    }

    @Operation(summary = "删除通道所有录像（仅管理员）")
    @PostMapping("/removechannel")
    public ApiResponse<DeletedVo> removeChannel(@RequestParam String serial,
                                                @RequestParam(required = false) String code) {
        StpUtil.checkPermission(Permissions.CLOUD_DELETE);
        String channelId = DeviceRef.channelOf(serial, code);
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

    @Operation(summary = "按天删除（仅管理员）")
    @PostMapping("/removedaily")
    public ApiResponse<DeletedVo> removeDaily(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestParam String period) {
        StpUtil.checkPermission(Permissions.CLOUD_DELETE);
        String channelId = DeviceRef.channelOf(serial, code);
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

    @Operation(summary = "重要标记")
    @PostMapping("/setimportant")
    public ApiResponse<Void> setImportant(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam String period,
                                          @RequestParam Boolean important) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        GbRecord record = recordService.findByPeriod(channelId, period);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + period);
        }
        recordService.setImportant(record.getRecordId(), important);
        return ApiResponse.ok();
    }

    @Operation(summary = "录像点播：把 MP4 拉入 ZLM 转协议输出（返回 WebRTC/FLV/HLS 等播放地址）")
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
     * 组装点播响应体。抽出来是因为分享页 {@code /share/{token}} 免登录，
     * 直接调 {@link #play(String)} 会撞上它的 {@code StpUtil.checkLogin()}（令牌校验已过，却在拼地址时 401）。
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

    @Operation(summary = "停止录像点播")
    @PostMapping("/stopplay")
    public ApiResponse<Void> stopPlay(@RequestParam String recordId) {
        StpUtil.checkLogin();
        recordService.stopCloudRecordPlay(recordId);
        return ApiResponse.ok();
    }

    @Operation(summary = "下载录像文件")
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
     * 录像文件流：浏览器 {@code <video>} 直接播放，也用于下载。用原生 video 而非 jessibuca，
     * 它天然支持 HTTP Range 拖动定位（实测 357MB / 1 小时 / moov 在文件尾，canplay 5.3 秒就绪）。
     * 三处关键：{@code inline} 而非默认 attachment；必须支持 Range（否则每次定位重下整文件）；
     * token 走 query（{@code <video src>} 带不了自定义头，Sa-Token 会读 query）。
     * 对象存储里的录像直接 302 到签名地址，不经平台转发。
     */
    @Operation(summary = "录像文件流：浏览器  直接播放，也用于下载")
    @GetMapping("/file/{recordId}")
    public void file(@PathVariable String recordId,
                     @RequestParam(value = "download", required = false) String download,
                     jakarta.servlet.http.HttpServletRequest request,
                     jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        StpUtil.checkLogin();
        GbRecord record = recordService.findRecord(recordId);
        if (record == null) {
            throw BizException.notFound("录像不存在：" + recordId);
        }
        // 对象存储：302 到签名地址，浏览器直连 OSS，不再经平台转发
        if (ObjectStorageService.TYPE_OSS.equals(record.getStorageType())) {
            String url = recordService.downloadUrl(record);
            if (url == null || url.isBlank() || url.startsWith("/api/")) {
                throw BizException.badRequest("该录像已归档到对象存储，但拿不到可直连的地址");
            }
            response.sendRedirect(url);
            return;
        }
        // filePath 可能为 null：录制中途被停、回调未到时以 filePath=null 入库。
        // 直接 Path.of(null) 会抛 NPE → 500，前端只看到「服务器错误」，无从排查。
        String filePath = record.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw BizException.notFound("该录像尚未生成文件（可能录制中断）：" + recordId);
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw BizException.notFound("录像文件已丢失：" + filePath);
        }
        long size = Files.size(path);

        String fileName = record.getFileName() == null ? recordId
                : record.getFileName().replace("\"", "");
        response.setContentType("video/mp4");
        // 默认 inline（让 <video> 就地播放）；带 download=1 时改成 attachment
        response.setHeader("Content-Disposition",
                "1".equals(download)
                        ? "attachment; filename=\"" + fileName + "\""
                        : "inline; filename=\"" + fileName + "\"");
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
                // 按 RFC 9110 §14.4 返回 206 + Content-Range。早先「起点为 0 就回 200 全量」
                // 反而更慢：200 表示这是完整内容，浏览器无法再发 Range，只能下完整个文件才肯播。
                // 回 206 后可先取文件尾（找 moov），再按需取 mdat，秒开。
                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        response.setContentLengthLong(len);
        writeRange(path, start, len, response.getOutputStream());
    }

    /**
     * 把文件的 {@code [start, start+len)} 区间写到响应流。不用 Resource / ResourceRegion：
     * InputStreamResource 从文件头读，请求 {@code bytes=3244032-} 会返回开头的字节，
     * Chrome 拿到错位数据后报 {@code DEMUXER_ERROR_COULD_NOT_OPEN}；ResourceRegion 与
     * StreamingResponseBody 都报 {@code No converter}。直接操作 HttpServletResponse 最稳。
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

    /** 解析 Range 头（只支持单区间）。格式非法或越界返回 {@code null}。 */
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
     * 指定时间段录像播放 / 下载。GBS 用 ffmpeg 把区间内多段合成一个 MP4；本实现不引入 ffmpeg 依赖，
     * 返回区间内的录像段列表由前端按序播放，需要单文件合成时再接入 ffmpeg。
     */
    @Operation(summary = "指定时间段录像播放 / 下载")
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
        // 本地录像：回填静态播放地址，前端可直接 <video src> 秒开
        if (ObjectStorageService.TYPE_LOCAL.equals(r.getStorageType()) && r.getEndTime() != null) {
            item.setPlayUrl(spaWebConfiguration.toStaticUrl(r.getFilePath()));
        }
        // 下载地址尽量与播放地址用同一个 URL：前端可直接 <a download> 让浏览器原生下载
        // （有进度、可续传、不占内存）。早先本地录像走 /file/{recordId}，前端只能 fetch+blob
        // 把整个文件读进内存（136MB 录像吃 136MB+，无进度）。对象存储仍是签名外链。
        if (withDownloadUrl && r.getEndTime() != null && recordService.fileAvailable(r)) {
            String playUrl = item.getPlayUrl();
            item.setDownloadUrl(playUrl != null ? playUrl : recordService.downloadUrl(r));
        }
        return item;
    }

    // ==================== 通道快照 ====================

    /**
     * 服务端抓拍：拉流 → ffmpeg 解码 → 存盘。这是唯一的快照来源：现场是 H265，
     * 只有真正解码才能出图；用外部 ffmpeg 进程完成（jar 增量 0，理由见 {@link FfmpegSnapService}）。
     */
    @Operation(summary = "服务端抓拍：拉流 → ffmpeg 解码 → 存盘")
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
     * 读取通道快照。<b>不校验登录态</b>：快照直接放进 {@code <img src>}，
     * {@code <img>} 不带自定义头，带鉴权就永远加载不出来。
     */
    @Operation(summary = "读取通道快照")
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
     * 生成某段录像的分享链接。对齐 LiveGBS 的分享页：详情页点分享 → 免登录链接，
     * 打开就能看这一段录像。
     */
    @Operation(summary = "生成某段录像的分享链接")
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
     * 按分享令牌取录像信息（<b>免登录</b>）。路径在 {@code SaTokenConfiguration} 的放行名单里。
     */
    @Operation(summary = "按分享令牌取录像信息（免登录）")
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
        // 用 playVo 而不是 play：后者带 checkLogin()，免登录访客会 401
        CloudPlayVo play = playVo(record, record.getRecordId());
        vo.put("webrtc", play.getWebrtc());
        vo.put("flv", play.getFlv());
        vo.put("wsFlv", play.getWsFlv());
        vo.put("hls", play.getHls());
        return ApiResponse.ok(vo);
    }
}
