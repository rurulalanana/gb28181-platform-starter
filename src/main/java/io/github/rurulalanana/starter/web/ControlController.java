package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.service.ControlService;
import io.github.rurulalanana.starter.vo.ChannelNavigateVo;
import io.github.rurulalanana.starter.vo.ControlResultVo;
import io.github.rurulalanana.starter.vo.DeviceUpgradeVo;
import io.github.rurulalanana.starter.vo.SnapshotConfigVo;
import io.github.rurulalanana.starter.vo.TalkVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 设备控制接口。路由前缀 {@code /api/v1/control}：
 * <pre>
 * ptz / fi / preset / homeposition / navigate / dragzoomin / dragzoomout / iframe
 * teleboot / record / guard / wiper / filllight / alarmnotify / alarmreset / synctime
 * deviceconfig / devicesubscribe / talk
 * 2022 扩展：ptzprecise / osdconfig / snapshotconfig / deviceupgrade / formatsdcard / targettrack
 * </pre>
 *
 * <p>控制命令均为「发出即返回」：设备是否接受由 MANSCDP 应答决定，
 * 确认结果请查 {@code /api/v1/device/statuslog} 或抓包。
 */
@Slf4j
@RestController
@Tag(name = "云台与设备控制", description = "方向、预置位、巡航、拉框缩放、重启")
@RequestMapping("/api/v1/control")
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;
    private final Gb28181Properties props;
    /** 预置位自定义名称落库用（设备只存编号，名字是平台侧数据）。 */
    private final io.github.rurulalanana.starter.service.DeviceService deviceService;

    // ==================== 云台 ====================

    @Operation(summary = "云台控制")
    @PostMapping("/ptz")
    public ApiResponse<Void> ptz(@RequestParam String serial,
                                 @RequestParam(required = false) String code,
                                 @RequestParam String command,
                                 @RequestParam(defaultValue = "129") Integer speed) {
        StpUtil.checkLogin();
        controlService.ptz(channel(serial, code), command, speed);
        return ApiResponse.ok();
    }

    @Operation(summary = "焦点光圈控制")
    @PostMapping("/fi")
    public ApiResponse<Void> focusIris(@RequestParam String serial,
                                       @RequestParam(required = false) String code,
                                       @RequestParam String command,
                                       @RequestParam(defaultValue = "129") Integer speed) {
        StpUtil.checkLogin();
        controlService.focusIris(channel(serial, code), command, speed);
        return ApiResponse.ok();
    }

    @Operation(summary = "精确云台控制（2022）")
    @PostMapping("/ptzprecise")
    public ApiResponse<Void> ptzPrecise(@RequestParam String serial,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(defaultValue = "0") Double pan,
                                        @RequestParam(defaultValue = "0") Double tilt,
                                        @RequestParam(defaultValue = "1") Double zoom) {
        StpUtil.checkLogin();
        controlService.ptzPrecise(channel(serial, code), pan, tilt, zoom);
        return ApiResponse.ok();
    }

    @Operation(summary = "拉框放大")
    @PostMapping("/dragzoomin")
    public ApiResponse<ControlResultVo> dragZoomIn(@RequestParam String serial,
                                                   @RequestParam(required = false) String code,
                                                   @RequestParam Integer length,
                                                   @RequestParam Integer width,
                                                   @RequestParam Integer midpointx,
                                                   @RequestParam Integer midpointy,
                                                   @RequestParam Integer lengthx,
                                                   @RequestParam Integer lengthy) {
        StpUtil.checkLogin();
        return ApiResponse.ok(controlService.dragZoom(channel(serial, code), true,
                length, width, midpointx, midpointy, lengthx, lengthy));
    }

    @Operation(summary = "拉框缩小")
    @PostMapping("/dragzoomout")
    public ApiResponse<ControlResultVo> dragZoomOut(@RequestParam String serial,
                                                    @RequestParam(required = false) String code,
                                                    @RequestParam Integer length,
                                                    @RequestParam Integer width,
                                                    @RequestParam Integer midpointx,
                                                    @RequestParam Integer midpointy,
                                                    @RequestParam Integer lengthx,
                                                    @RequestParam Integer lengthy) {
        StpUtil.checkLogin();
        return ApiResponse.ok(controlService.dragZoom(channel(serial, code), false,
                length, width, midpointx, midpointy, lengthx, lengthy));
    }

    // ==================== 预置位 / 巡航 / 看守位 ====================

    /**
     * 预置位控制。command：set 设置 / goto 调用 / remove 删除；preset 取值 1~255。
     * name 只在 set 时生效且仅存平台侧（{@code gb_preset}）—— 设备只存编号，
     * 各厂商对 {@code PresetName} 的支持差异很大。
     */
    @Operation(summary = "预置位控制")
    @PostMapping("/preset")
    public ApiResponse<Void> preset(@RequestParam String serial,
                                    @RequestParam(required = false) String code,
                                    @RequestParam String command,
                                    @RequestParam Integer preset,
                                    @RequestParam(required = false) String name) {
        StpUtil.checkLogin();
        String channelId = channel(serial, code);
        controlService.preset(channelId, command, preset);
        // 名称必须真的落库：早先只打一行日志，用户设置备注后刷新又变回「预置位 N」。
        if (name != null && !name.isBlank() && "set".equalsIgnoreCase(command)) {
            deviceService.savePresetName(channelId, preset, name.trim());
        }
        // 删除时一并清掉平台侧名称，否则设备槽位已空但界面仍显示旧名称（幽灵备注）。
        if ("remove".equalsIgnoreCase(command) || "delete".equalsIgnoreCase(command)) {
            deviceService.savePresetName(channelId, preset, null);
        }
        return ApiResponse.ok();
    }

    @Operation(summary = "看守位控制")
    @PostMapping("/homeposition")
    public ApiResponse<Void> homePosition(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam(defaultValue = "false") Boolean enabled,
                                          @RequestParam(required = false) Integer resettime,
                                          @RequestParam(required = false) Integer presetindex) {
        StpUtil.checkLogin();
        controlService.homePosition(channel(serial, code), enabled, resettime, presetindex);
        return ApiResponse.ok();
    }

    /** {@code command=query} 返回巡航组列表结构，数据由设备 MANSCDP 应答异步返回。 */
    @Operation(summary = "巡航控制")
    @PostMapping("/navigate")
    public ApiResponse<ChannelNavigateVo> navigate(@RequestParam String serial,
                                                   @RequestParam(required = false) String code,
                                                   @RequestParam String command,
                                                   @RequestParam(required = false) Integer groupid,
                                                   @RequestParam(required = false) Integer preset,
                                                   @RequestParam(required = false) Integer speed,
                                                   @RequestParam(required = false) Integer stay,
                                                   @RequestParam(required = false) String name) {
        StpUtil.checkLogin();
        String channelId = channel(serial, code);
        controlService.navigate(channelId, command, groupid, preset, speed, stay, name);
        if (!"query".equalsIgnoreCase(command)) {
            return ApiResponse.ok();
        }
        ChannelNavigateVo vo = new ChannelNavigateVo();
        vo.setDeviceId(channelId);
        vo.setSumNum(0);
        vo.setGroupList(List.of());
        vo.setNote("已下发巡航查询，结果由设备 MANSCDP 应答异步返回");
        return ApiResponse.ok(vo);
    }

    // ==================== 辅助开关 ====================

    @Operation(summary = "雨刷开关")
    @PostMapping("/wiper")
    public ApiResponse<Void> wiper(@RequestParam String serial,
                                   @RequestParam(required = false) String code,
                                   @RequestParam String command,
                                   @RequestParam(required = false) String ptzcmd) {
        StpUtil.checkLogin();
        controlService.wiper(channel(serial, code), command);
        return ApiResponse.ok();
    }

    @Operation(summary = "补光灯开关")
    @PostMapping("/filllight")
    public ApiResponse<Void> fillLight(@RequestParam String serial,
                                       @RequestParam(required = false) String code,
                                       @RequestParam String command,
                                       @RequestParam(defaultValue = "0") Integer index,
                                       @RequestParam(required = false) String ptzcmd) {
        StpUtil.checkLogin();
        controlService.fillLight(channel(serial, code), command, index);
        return ApiResponse.ok();
    }

    // ==================== 设备动作 ====================

    @Operation(summary = "设备远程重启")
    @PostMapping("/teleboot")
    public ApiResponse<Void> teleBoot(@RequestParam String serial,
                                      @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.teleBoot(channel(serial, code));
        return ApiResponse.ok();
    }

    @Operation(summary = "设备端录像控制")
    @PostMapping("/record")
    public ApiResponse<Void> record(@RequestParam String serial,
                                    @RequestParam(required = false) String code,
                                    @RequestParam String command,
                                    @RequestParam(defaultValue = "0") Integer streamnumber) {
        StpUtil.checkLogin();
        controlService.recordControl(channel(serial, code), command, streamnumber);
        return ApiResponse.ok();
    }

    @Operation(summary = "布防撤防")
    @PostMapping("/guard")
    public ApiResponse<Void> guard(@RequestParam String serial,
                                   @RequestParam(required = false) String code,
                                   @RequestParam String command) {
        StpUtil.checkLogin();
        controlService.guard(channel(serial, code), command);
        return ApiResponse.ok();
    }

    @Operation(summary = "强制关键帧")
    @PostMapping("/iframe")
    public ApiResponse<Void> forceIFrame(@RequestParam String serial,
                                         @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.forceIFrame(channel(serial, code));
        return ApiResponse.ok();
    }

    @Operation(summary = "存储卡格式化（2022）")
    @PostMapping("/formatsdcard")
    public ApiResponse<Void> formatSdCard(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam(defaultValue = "1") Integer id) {
        StpUtil.checkLogin();
        controlService.formatSdCard(channel(serial, code), id);
        return ApiResponse.ok();
    }

    // ==================== 报警 ====================

    @Operation(summary = "报警通知下发")
    @PostMapping("/alarmnotify")
    public ApiResponse<Void> alarmNotify(@RequestParam String serial,
                                         @RequestParam(required = false) String code,
                                         @RequestParam Integer priority,
                                         @RequestParam Integer method,
                                         @RequestParam(required = false) String description,
                                         @RequestParam(required = false) Double longitude,
                                         @RequestParam(required = false) Double latitude,
                                         @RequestParam(required = false) Integer type) {
        StpUtil.checkLogin();
        controlService.alarmNotify(channel(serial, code), priority, method, type,
                description, longitude, latitude);
        return ApiResponse.ok();
    }

    @Operation(summary = "报警复位")
    @PostMapping("/alarmreset")
    public ApiResponse<Void> alarmReset(@RequestParam String serial,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(value = "alarm_method", required = false) String alarmMethod,
                                        @RequestParam(value = "alarm_type", required = false) String alarmType) {
        StpUtil.checkLogin();
        controlService.alarmReset(channel(serial, code));
        return ApiResponse.ok();
    }

    /**
     * 设备校时。设备时钟漂移会让上报的录像时间与平台检索区间对不上，
     * 表现为「按时间段查不到录像，但录像确实存在」。
     * {@code time} 留空用平台当前时间，格式 {@code yyyy-MM-dd'T'HH:mm:ss}。
     */
    @Operation(summary = "设备校时")
    @PostMapping("/synctime")
    public ApiResponse<Void> syncTime(@RequestParam String serial,
                                      @RequestParam(required = false) String code,
                                      @RequestParam(required = false) String time) {
        StpUtil.checkLogin();
        java.time.LocalDateTime t = null;
        if (time != null && !time.isBlank()) {
            try {
                t = java.time.LocalDateTime.parse(time.trim().replace(' ', 'T'));
            } catch (Exception e) {
                throw BizException.badRequest(
                        "time 格式应为 yyyy-MM-ddTHH:mm:ss，收到：" + time);
            }
        }
        controlService.syncTime(channel(serial, code), t);
        return ApiResponse.ok();
    }

    // ==================== 配置 / 订阅 ====================

    @Operation(summary = "设备配置")
    @PostMapping("/deviceconfig")
    public ApiResponse<Void> deviceConfig(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestParam String xml) {
        StpUtil.checkLogin();
        controlService.deviceConfig(channel(serial, code), xml);
        return ApiResponse.ok();
    }

    @Operation(summary = "设备订阅")
    @PostMapping("/devicesubscribe")
    public ApiResponse<Void> subscribe(@RequestParam String serial,
                                       @RequestParam(required = false) String code,
                                       @RequestParam String type,
                                       @RequestParam(defaultValue = "3600") Integer expires,
                                       @RequestParam(defaultValue = "5") Integer interval,
                                       @RequestParam(required = false) String alarm_method,
                                       @RequestParam(required = false) String alarm_type) {
        StpUtil.checkLogin();
        controlService.subscribe(channel(serial, code), type, expires, interval);
        return ApiResponse.ok();
    }

    // ==================== 语音喊话 ====================

    /**
     * 语音喊话。标准流程：平台发 {@code Broadcast} 通知 → 设备回 INVITE → 平台应答并建立下行通道 →
     * 音频持续推送。本接口只完成第一步，实时音频请用 {@code ws-talk}。
     */
    @Operation(summary = "语音喊话")
    @PostMapping("/talk")
    public ApiResponse<TalkVo> talk(@RequestParam String serial,
                                    @RequestParam(required = false) String code,
                                    @RequestParam(required = false) String audio,
                                    @RequestParam(defaultValue = "pcm") String format) {
        StpUtil.checkLogin();
        String channelId = channel(serial, code);
        if (audio != null && !audio.isBlank()) {
            try {
                byte[] pcm = Base64.getDecoder().decode(audio);
                log.info("收到喊话音频: channel={} format={} bytes={}", channelId, format, pcm.length);
            } catch (IllegalArgumentException e) {
                throw BizException.badRequest("audio 必须是 Base64 编码的音频数据");
            }
        }
        controlService.talk(channelId, props.getPlatformId());
        TalkVo vo = new TalkVo();
        vo.setOk(true);
        vo.setChannelId(channelId);
        vo.setFormat(format);
        vo.setNote("已发送语音广播通知；设备回 INVITE 后由媒体服务建立下行通道。"
                + "持续音频请使用 WebSocket 接口");
        return ApiResponse.ok(vo);
    }

    // ==================== 2022 扩展 ====================

    @Operation(summary = "OSD 配置（2022）")
    @PostMapping("/osdconfig")
    public ApiResponse<Void> osdConfig(@RequestParam String serial,
                                       @RequestParam(required = false) String code,
                                       @RequestParam(value = "time_enable", required = false) Integer timeEnable,
                                       @RequestParam(value = "time_type", required = false) Integer timeType,
                                       @RequestParam(value = "time_x", required = false) Integer timeX,
                                       @RequestParam(value = "time_y", required = false) Integer timeY,
                                       @RequestParam(value = "text_enable", required = false) Integer textEnable,
                                       @RequestParam(required = false) String text,
                                       @RequestParam(value = "text_x", required = false) Integer textX,
                                       @RequestParam(value = "text_y", required = false) Integer textY,
                                       @RequestParam(required = false) Integer width,
                                       @RequestParam(required = false) Integer length) {
        StpUtil.checkLogin();
        controlService.osdConfig(channel(serial, code), timeEnable, timeType, timeX, timeY,
                textEnable, text, textX, textY, width, length);
        return ApiResponse.ok();
    }

    @Operation(summary = "图像抓拍配置（2022）")
    @PostMapping("/snapshotconfig")
    public ApiResponse<SnapshotConfigVo> snapshotConfig(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(value = "snap_num", defaultValue = "1") Integer snapNum,
            @RequestParam(defaultValue = "1") Integer interval,
            @RequestParam(value = "upload_url", required = false) String uploadUrl) {
        StpUtil.checkLogin();
        controlService.snapshotConfig(channel(serial, code), snapNum, interval, uploadUrl);
        SnapshotConfigVo vo = new SnapshotConfigVo();
        vo.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        vo.setSnapShotList(List.of());
        vo.setDownloadList(List.of());
        vo.setNote("抓拍配置已下发；图像由设备通过 UploadSnapShotFinished 通知回传");
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "设备软件升级（2022）")
    @PostMapping("/deviceupgrade")
    public ApiResponse<DeviceUpgradeVo> deviceUpgrade(@RequestParam String serial,
                                                      @RequestParam(required = false) String code,
                                                      @RequestParam String firmware,
                                                      @RequestParam String fileurl,
                                                      @RequestParam String manufacturer) {
        StpUtil.checkLogin();
        ControlResultVo ack = controlService.deviceUpgrade(
                channel(serial, code), firmware, fileurl, manufacturer);
        DeviceUpgradeVo vo = new DeviceUpgradeVo();
        // 有应答就报应答（OK/ERROR），没有就 PENDING —— 三种状态互斥，不再一律 PENDING
        vo.setUpgradeResult(ack.isAnswered()
                ? (ack.isSent() ? ack.getResult() : null) : "PENDING");
        vo.setFirmware(firmware);
        vo.setSessionId(ack.getSessionId());
        vo.setNote(ack.getNote()
                + "；升级是否成功由设备通过 DeviceUpgradeResult 通知回传（session="
                + ack.getSessionId() + "）");
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "目标跟踪控制（2022）")
    @PostMapping("/targettrack")
    public ApiResponse<ControlResultVo> targetTrack(@RequestParam String serial,
                                                    @RequestParam(required = false) String code,
                                                    @RequestParam(value = "target_track") String targetTrack,
                                                    @RequestParam(value = "device_id2", required = false) String deviceId2,
                                                    @RequestParam(required = false) Integer length,
                                                    @RequestParam(required = false) Integer width,
                                                    @RequestParam(required = false) Integer midpointx,
                                                    @RequestParam(required = false) Integer midpointy,
                                                    @RequestParam(required = false) Integer lengthx,
                                                    @RequestParam(required = false) Integer lengthy) {
        StpUtil.checkLogin();
        return ApiResponse.ok(controlService.targetTrack(channel(serial, code), targetTrack, deviceId2,
                length, width, midpointx, midpointy, lengthx, lengthy));
    }

    // ==================== 内部 ====================

    /** 解析通道编号：{@code code} 为空时回退 {@code serial}。 */
    private static String channel(String serial, String code) {
        if (serial == null || serial.isBlank()) {
            throw BizException.badRequest("缺少参数 serial");
        }
        return DeviceRef.channelOf(serial, code);
    }
}
