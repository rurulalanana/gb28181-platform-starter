package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.vo.CloudRecordVo;
import io.github.rurulalanana.starter.vo.RecordFileListVo;
import io.github.rurulalanana.starter.vo.RecordStartVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时录像接口。
 *
 * <p>「实时录像」是对<b>正在保活的流</b>边播边录：录制期间不产出可播文件，
 * 停止后返回 MP4 下载链接。
 *
 * <pre>
 * POST /api/v1/record/start   开始实时录像
 * POST /api/v1/record/stop    停止并返回文件列表
 * GET  /api/v1/record/list    查询当前录像文件
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/record")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    /** 开始实时录像。 */
    @PostMapping("/start")
    public ApiResponse<RecordStartVo> start(@RequestParam String serial,
                                            @RequestParam(required = false) String code,
                                            @RequestParam(required = false) String streamid,
                                            @RequestParam(defaultValue = "0") Integer interval) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        if (recordService.isRecording(channelId)) {
            throw BizException.badRequest("该通道已在录像中：" + channelId);
        }
        // 传设备编码：流未建立时服务内部会自动点播并等流上线
        GbRecord record = recordService.startCloudRecord(serial, channelId);
        RecordStartVo vo = new RecordStartVo();
        vo.setRecordId(record.getRecordId());
        vo.setChannelId(channelId);
        vo.setStartTime(TimeFormats.toCompact(record.getStartTime()));
        vo.setNote("录像进行中，停止后方可下载（record/stop）");
        return ApiResponse.ok(vo);
    }

    /** 停止实时录像。 */
    @PostMapping("/stop")
    public ApiResponse<RecordFileListVo> stop(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestParam(required = false) String streamid) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        GbRecord record = recordService.stopCloudRecord(channelId);
        RecordFileListVo vo = new RecordFileListVo();
        vo.setChannelId(channelId);
        if (record == null) {
            vo.setRecordCount(0);
            vo.setRecordList(List.of());
            return ApiResponse.ok(vo);
        }
        vo.setRecordCount(1);
        vo.setRecordList(List.of(fileView(record)));
        return ApiResponse.ok(vo);
    }

    /** 查询实时录像文件。 */
    @GetMapping("/list")
    public ApiResponse<RecordFileListVo> list(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestParam(required = false) String streamid) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        boolean recording = recordService.isRecording(channelId);
        List<CloudRecordVo> list = new ArrayList<>();
        for (GbRecord r : recordService.cloudRecords(channelId, null, null)) {
            list.add(fileView(r));
        }
        RecordFileListVo vo = new RecordFileListVo();
        vo.setChannelId(channelId);
        vo.setRecording(recording);
        vo.setRecordCount(list.size());
        vo.setRecordList(list);
        return ApiResponse.ok(vo);
    }

    // ==================== 内部 ====================

    private CloudRecordVo fileView(GbRecord r) {
        CloudRecordVo item = new CloudRecordVo();
        item.setRecordId(r.getRecordId());
        item.setChannelId(r.getChannelId());
        item.setStartAt(TimeFormats.toCompact(r.getStartTime()));
        item.setEndTime(TimeFormats.toCompact(r.getEndTime()));
        item.setFileSize(r.getFileSize());
        item.setDuration(r.getDuration());
        item.setStorageType(r.getStorageType());
        item.setUploadState(r.getUploadState());
        item.setRecording(r.getEndTime() == null);
        // 录制中的文件尚未定稿，不给下载链接
        if (r.getEndTime() != null && recordService.fileAvailable(r)) {
            item.setDownloadUrl(recordService.downloadUrl(r));
        }
        return item;
    }
}
