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
    /** 用于把录像文件路径换算成 {@code /record/...} 静态地址。 */
    private final io.github.rurulalanana.starter.config.SpaWebConfiguration spaWebConfiguration;

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
            /*
             * 要下载地址。
             *
             * <p>早先这里传 false，理由是「列表页不必查文件是否存在」。
             * 但前端的下载按钮判据就是 {@code downloadUrl} 非空 ——
             * 不填就等于把下载功能整体关掉，界面上每个「下载」都是灰的
             * （用户看到的就是「为啥下载按钮都是灰的」）。
             *
             * <p>代价只有一次 {@code Files.exists}（几十条记录，微秒级），
             * 换来「文件真没了就不给下载按钮」的正确提示。
             */
            list.add(toItem(r, true));
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
     * 录像文件流：浏览器 {@code <video>} 直接播放，也用于下载。
     *
     * <p><b>为什么用原生 {@code <video>} 而不是 jessibuca</b>：
     * 原生 video 天然支持 <b>HTTP Range → 拖动进度条直接定位</b>，
     * 不需要服务端做 seek。实测 357MB / 1 小时 / moov 在文件尾的录像，
     * {@code canplay} 5.3 秒就绪、{@code readyState=4}，没有任何问题。
     *
     * <p><b>三处处理</b>：
     * <ul>
     *   <li>{@code Content-Disposition: inline} —— 默认的 attachment 会让浏览器
     *       直接下载而不是播放；下载时加 {@code ?download=1} 换回 attachment；</li>
     *   <li><b>支持 Range</b> —— 拖动进度条时浏览器会发 {@code Range} 请求，
     *       不支持的话每次定位都要重下整个文件；</li>
     *   <li><b>token 走 query</b> —— {@code <video src>} 请求带不了自定义头，
     *       Sa-Token 默认会从 query 参数里读 token（{@code is-read-body}），
     *       前端把 token 拼在 URL 上即可。</li>
     * </ul>
     *
     * <p><b>对象存储里的录像直接 302 到签名地址</b>：文件在 OSS 上，
     * 再经平台转发一遍纯属浪费（还多一跳延迟）。签名地址自带有效期，
     * 浏览器拿到后自己直连 OSS。
     */
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
        // filePath 可能为 null：库里的条目来自两处 —— ZLM 回调回填的（有路径）、
        // 以及定期对账按磁盘文件补登记的（也有路径）。但「录制中途被停、回调未到」
        // 的条目会以 filePath=null 入库（见 RecordingService#stopRecording）。
        // 直接 Path.of(null) 会抛 NPE → 全局异常处理器返回 500，
        // 前端看到的是「服务器错误」而不是「录像文件不存在」，无从排查。
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
                /*
                 * 按 RFC 9110 §14.4 返回 206 + Content-Range。
                 *
                 * <p><b>早先这里是「起点为 0 就回 200 全量」</b>，实测反而更慢：
                 * 回 200 等于告诉浏览器「这就是完整内容」，它没法再发 Range，
                 * 只能从头到尾把整个文件拉下来才肯播 ——
                 * 对 1 小时、357MB 的录像就是「必须下完才能看」。
                 *
                 * <p>回 206 之后，浏览器可以先取文件尾（找 moov），
                 * 再按需取 mdat 区间，秒开。
                 */
                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
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
        // 本地录像：回填静态播放地址，前端可直接 <video src> 秒开
        if (ObjectStorageService.TYPE_LOCAL.equals(r.getStorageType()) && r.getEndTime() != null) {
            item.setPlayUrl(spaWebConfiguration.toStaticUrl(r.getFilePath()));
        }
        /*
         * 下载地址：与播放地址<b>尽量是同一个 URL</b>。
         *
         * <p>同一个文件本来就该用同一个地址取 —— 前端拿到它就能直接
         * {@code <a download>} 让浏览器原生下载（有进度、可续传、不占内存）。
         *
         * <p>早先本地录像走 {@code /api/v1/cloudrecord/file/{recordId}}：
         * 那个接口由本进程转发文件流，协议上没问题，但前端为了带登录头
         * 只能 {@code fetch + blob} 把整个文件读进内存 —— 136MB 的录像
         * 直接吃掉 136MB+ 内存，且全程没有任何进度反馈，用户以为卡死了。
         * 有了 nginx sendfile 就直接给静态路径。
         *
         * <p>对象存储（OSS）仍是签名/外链 URL，跨域，交给浏览器新窗口下载。
         */
        if (withDownloadUrl && r.getEndTime() != null && recordService.fileAvailable(r)) {
            String playUrl = item.getPlayUrl();
            item.setDownloadUrl(playUrl != null ? playUrl : recordService.downloadUrl(r));
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
