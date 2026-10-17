package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.storage.ObjectStorageService;
import io.github.rurulalanana.starter.vo.CloudChannelListVo;
import io.github.rurulalanana.starter.vo.CloudChannelVo;
import io.github.rurulalanana.starter.vo.CloudDailyVo;
import io.github.rurulalanana.starter.vo.CloudFlagsVo;
import io.github.rurulalanana.starter.vo.CloudMonthVo;
import io.github.rurulalanana.starter.vo.CloudPlayVo;
import io.github.rurulalanana.starter.vo.CloudRecordVo;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.DownloadVo;
import io.github.rurulalanana.starter.vo.VideoRangeVo;
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
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cloudrecord")
@RequiredArgsConstructor
public class CloudRecordController {

    private final RecordService recordService;
    private final DeviceService deviceService;

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
        return ApiResponse.ok(vo);
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
     * 录像文件流下载（配合 {@code download} 返回的本地下载地址）。
     *
     * <p>对象存储中的录像不经过本接口（download 直接返回签名/外链地址）。
     */
    @GetMapping("/file/{recordId}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> file(
            @PathVariable String recordId) {
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
        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.FileSystemResource(path);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "video/mp4")
                .header("Content-Disposition",
                        "attachment; filename=\"" + record.getFileName() + "\"")
                .body(resource);
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
}
