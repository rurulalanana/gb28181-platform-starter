package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.PtzEncoder;
import io.github.rurulalanana.starter.common.TimeFormats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 设备控制服务（GB/T 28181—2022 附录 A.2.3 Control 命令族）。
 *
 * <p>覆盖 GBS 的 {@code /api/v1/control/*} 接口：云台、焦点光圈、预置位、巡航、
 * 看守位、录像控制、布撤防、重启、强制关键帧、格式化存储卡、报警复位、
 * 拉框缩放、雨刷补光、OSD、抓拍、升级、订阅、喊话。
 *
 * <p>PTZ 指令编码见 {@link PtzEncoder}（依赖模块的 {@code PtzCommand}
 * 无法表达预置位编号 1~255 与三种预置位操作，故自行编码）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlService {

    /** 直连设备的默认云台地址。 */
    private static final int DEFAULT_ADDRESS = 1;

    private final Gb28181SipClientProvider clientProvider;

    private Gb28181SipClient client() {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法下发控制命令");
        }
        return client;
    }

    // ==================== 云台 ====================

    /**
     * 云台方向控制。
     *
     * @param speed 速度 0~255，默认 129
     */
    public void ptz(String channelId, String command, Integer speed) {
        String cmd = normalize(command);
        int sp = speed == null ? 129 : Math.max(0, Math.min(255, speed));
        if ("stop".equals(cmd)) {
            client().ptzControl(channelId, PtzEncoder.stop(DEFAULT_ADDRESS));
            return;
        }
        int bits = switch (cmd) {
            case "left" -> PtzEncoder.LEFT;
            case "right" -> PtzEncoder.RIGHT;
            case "up" -> PtzEncoder.UP;
            case "down" -> PtzEncoder.DOWN;
            case "upleft" -> PtzEncoder.UP | PtzEncoder.LEFT;
            case "upright" -> PtzEncoder.UP | PtzEncoder.RIGHT;
            case "downleft" -> PtzEncoder.DOWN | PtzEncoder.LEFT;
            case "downright" -> PtzEncoder.DOWN | PtzEncoder.RIGHT;
            case "zoomin" -> PtzEncoder.ZOOM_IN;
            case "zoomout" -> PtzEncoder.ZOOM_OUT;
            default -> throw BizException.badRequest("不支持的云台指令：" + command);
        };
        client().ptzControl(channelId, PtzEncoder.direction(DEFAULT_ADDRESS, bits, sp));
    }

    /**
     * 焦点光圈控制。
     *
     * <p>标准 A.3 中焦点与光圈复用「变倍」位（Bit5/Bit4），
     * 由设备侧按镜头模式解释，故指令结构与变倍一致。
     */
    public void focusIris(String channelId, String command, Integer speed) {
        String cmd = normalize(command);
        int sp = speed == null ? 129 : Math.max(0, Math.min(255, speed));
        int bits = switch (cmd) {
            case "focusfar", "irisin" -> PtzEncoder.ZOOM_IN;
            case "focusnear", "irisout" -> PtzEncoder.ZOOM_OUT;
            case "stop" -> 0;
            default -> throw BizException.badRequest("不支持的焦点光圈指令：" + command);
        };
        client().ptzControl(channelId, PtzEncoder.direction(DEFAULT_ADDRESS, bits, sp));
    }

    /** 精确云台控制（2022）：角度 + 变焦倍数。 */
    public void ptzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
        client().ptzPrecise(channelId, pan, tilt, zoom);
    }

    /** 拉框放大 / 缩小。 */
    public void dragZoom(String channelId, boolean zoomIn, int length, int width,
                         int midpointX, int midpointY, int lengthX, int lengthY) {
        client().ptzControl(channelId, PtzEncoder.dragZoom(zoomIn, length, width,
                midpointX, midpointY, lengthX, lengthY));
    }

    // ==================== 预置位 ====================

    /** 预置位控制：{@code set} / {@code goto} / {@code remove}。 */
    public void preset(String channelId, String command, Integer preset) {
        if (preset == null) {
            throw BizException.badRequest("缺少预置位编号");
        }
        int op = switch (normalize(command)) {
            case "set" -> PtzEncoder.PRESET_SET;
            case "goto", "call" -> PtzEncoder.PRESET_CALL;
            case "remove", "delete" -> PtzEncoder.PRESET_REMOVE;
            default -> throw BizException.badRequest("不支持的预置位指令：" + command);
        };
        client().ptzControl(channelId, PtzEncoder.preset(DEFAULT_ADDRESS, op, preset));
    }

    /** 查询设备侧预置位列表。 */
    public void queryPresets(String channelId) {
        client().queryPresets(channelId);
    }

    // ==================== 巡航 / 看守位 ====================

    /**
     * 巡航控制。
     *
     * <p>{@code query} 只下发查询（结果走 MANSCDP 应答）；
     * {@code add}/{@code remove} 用 {@code CRUISE_SET} 指令携带组号与预置位；
     * {@code setspeed}/{@code setstay} 把参数写入数据位。
     */
    public void navigate(String channelId, String command, Integer groupId, Integer preset,
                         Integer speed, Integer stay, String name) {
        String cmd = normalize(command);
        int group = groupId == null ? 1 : groupId;
        switch (cmd) {
            case "query" -> client().sendMessage(channelId,
                    ManscdpBuilder.cruiseTrackListQuery(client().nextSn(), channelId));
            case "add", "remove" -> client().ptzControl(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group,
                            preset == null ? 0 : preset));
            case "setspeed" -> client().ptzControl(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group, speed == null ? 1 : speed));
            case "setstay" -> client().ptzControl(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group, stay == null ? 1 : stay));
            case "start" -> client().ptzControl(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_START, group, 0));
            case "stop" -> client().ptzControl(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_STOP, group, 0));
            default -> throw BizException.badRequest("不支持的巡航指令：" + command);
        }
        log.info("巡航控制: channel={} command={} group={} preset={} speed={} stay={} name={}",
                channelId, command, groupId, preset, speed, stay, name);
    }

    /** 查询巡航轨迹。 */
    public void queryCruiseTrack(String channelId, Integer number) {
        client().sendMessage(channelId, ManscdpBuilder.cruiseTrackQuery(
                client().nextSn(), channelId, number == null ? 0 : number));
    }

    /** 查询看守位信息。 */
    public void queryHomePosition(String channelId) {
        client().sendMessage(channelId, ManscdpBuilder.homePositionQuery(client().nextSn(), channelId));
    }

    /** 看守位控制。 */
    public void homePosition(String channelId, Boolean enabled, Integer resetTime, Integer presetIndex) {
        client().ptzControl(channelId, PtzEncoder.watchdog(DEFAULT_ADDRESS,
                Boolean.TRUE.equals(enabled),
                resetTime == null ? 0 : resetTime,
                presetIndex == null ? 1 : presetIndex));
    }

    // ==================== 辅助开关 ====================

    /** 雨刷开关。 */
    public void wiper(String channelId, String command) {
        boolean on = "on".equals(normalize(command));
        client().ptzControl(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, 0, on));
    }

    /** 补光灯开关。 */
    public void fillLight(String channelId, String command, Integer index) {
        boolean on = "on".equals(normalize(command));
        client().ptzControl(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS,
                index == null ? 1 : index, on));
    }

    // ==================== 设备控制 ====================

    /** 设备端录像控制：{@code start} / {@code stop}。 */
    public void recordControl(String channelId, String command, Integer streamNumber) {
        String value = switch (normalize(command)) {
            case "start" -> "Record";
            case "stop" -> "StopRecord";
            default -> throw BizException.badRequest("不支持的录像指令：" + command);
        };
        client().sendMessage(channelId,
                ManscdpBuilder.recordControl(client().nextSn(), channelId, value, streamNumber));
    }

    /** 布防 / 撤防。 */
    public void guard(String channelId, String command) {
        String value = switch (normalize(command)) {
            case "setguard" -> "SetGuard";
            case "resetguard" -> "ResetGuard";
            default -> throw BizException.badRequest("不支持的布防指令：" + command);
        };
        client().sendMessage(channelId, ManscdpBuilder.guardControl(client().nextSn(), channelId, value));
    }

    /** 设备远程重启（标准 A.2.3.1.3）。 */
    public void teleBoot(String channelId) {
        client().remoteBoot(channelId);
    }

    /** 强制关键帧（标准 A.2.3.1.7）。 */
    public void forceIFrame(String channelId) {
        client().forceIFrame(channelId);
    }

    /** 存储卡格式化（标准 A.2.3.1.13）：{@code id=0} 表示全部。 */
    public void formatSdCard(String channelId, Integer sdCardId) {
        client().formatSdCard(channelId, sdCardId == null ? 0 : sdCardId);
    }

    /** 报警复位（标准 A.2.3.1.9）。 */
    public void alarmReset(String channelId) {
        client().sendMessage(channelId, ManscdpBuilder.alarmReset(client().nextSn(), channelId));
    }

    /** 设备配置下发（2022）：直接透传 xml（由调用方按标准拼装配置段）。 */
    public void deviceConfig(String channelId, String xml) {
        if (xml == null || xml.isBlank()) {
            throw BizException.badRequest("配置内容不能为空");
        }
        client().sendMessage(channelId, xml);
    }

    /** 设备订阅（目录 / 报警 / 位置 / PTZ）。 */
    public void subscribe(String channelId, String type, Integer expires, Integer interval) {
        String event = switch (normalize(type)) {
            case "catalog" -> "Catalog";
            case "alarm" -> "Alarm";
            case "position", "mobileposition" -> "MobilePosition";
            case "ptz" -> "PTZPosition";
            default -> throw BizException.badRequest("不支持的订阅类型：" + type);
        };
        int exp = expires == null ? 3600 : expires;
        String body = switch (event) {
            case "MobilePosition" -> ManscdpBuilder.mobilePositionSubscribe(
                    client().nextSn(), channelId, interval);
            case "Catalog" -> ManscdpBuilder.catalogQuery(client().nextSn(), channelId);
            default -> null;
        };
        client().subscribe(channelId, event, exp, body);
        log.info("已发起订阅: channel={} event={} expires={}", channelId, event, exp);
    }

    /** 查询设备状态（标准 9.6）。 */
    public void queryDeviceStatus(String channelId) {
        client().queryDeviceStatus(channelId);
    }

    /** 查询设备信息（标准 9.5）。 */
    public void queryDeviceInfo(String channelId) {
        client().queryDeviceInfo(channelId);
    }

    /** 查询设备配置（标准 A.2.4.7）。 */
    public void queryConfig(String channelId, String configType) {
        client().sendMessage(channelId, ManscdpBuilder.configDownloadQuery(
                client().nextSn(), channelId, configType));
    }

    /** 查询存储卡状态（2022）。 */
    public void querySdCardStatus(String channelId) {
        client().sendMessage(channelId, ManscdpBuilder.sdCardStatusQuery(client().nextSn(), channelId));
    }

    /** 查询 PTZ 精确状态（2022）。 */
    public void queryPtzPosition(String channelId) {
        client().sendMessage(channelId, ManscdpBuilder.ptzPositionQuery(client().nextSn(), channelId));
    }

    // ==================== OSD / 抓拍 / 升级 / 目标跟踪 ====================

    /** 前端 OSD 配置（2022）。 */
    public void osdConfig(String channelId, Integer timeEnable, Integer timeType,
                          Integer timeX, Integer timeY, Integer textEnable, String text,
                          Integer textX, Integer textY, Integer width, Integer length) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceConfig</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<OSDConfig>")
                .append("<Length>").append(nz(length)).append("</Length>")
                .append("<Width>").append(nz(width)).append("</Width>")
                .append("<TimeX>").append(nz(timeX)).append("</TimeX>")
                .append("<TimeY>").append(nz(timeY)).append("</TimeY>")
                .append("<TimeEnable>").append(nz(timeEnable)).append("</TimeEnable>")
                .append("<TimeType>").append(nz(timeType)).append("</TimeType>")
                .append("<TextEnable>").append(nz(textEnable)).append("</TextEnable>")
                .append("<SumNum>").append(text == null || text.isBlank() ? 0 : 1).append("</SumNum>");
        if (text != null && !text.isBlank()) {
            sb.append("<Item><Text>").append(escape(text)).append("</Text>")
                    .append("<X>").append(nz(textX)).append("</X>")
                    .append("<Y>").append(nz(textY)).append("</Y></Item>");
        }
        sb.append("</OSDConfig></Control>");
        client().sendMessage(channelId, sb.toString());
    }

    /** 图像抓拍配置（2022）。 */
    public void snapshotConfig(String channelId, Integer snapNum, Integer interval, String uploadUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceConfig</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<SnapShotConfig>")
                .append("<SnapNum>").append(snapNum == null ? 1 : snapNum).append("</SnapNum>")
                .append("<Interval>").append(interval == null ? 1 : interval).append("</Interval>");
        if (uploadUrl != null && !uploadUrl.isBlank()) {
            sb.append("<UploadURL>").append(escape(uploadUrl)).append("</UploadURL>");
        }
        sb.append("<SessionID>").append(UUID.randomUUID().toString().replace("-", ""))
                .append("</SessionID>")
                .append("</SnapShotConfig></Control>");
        client().sendMessage(channelId, sb.toString());
    }

    /** 设备软件升级（2022）。 */
    public void deviceUpgrade(String channelId, String firmware, String fileUrl, String manufacturer) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceUpgrade</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<Firmware>").append(escape(nz(firmware))).append("</Firmware>")
                .append("<FileURL>").append(escape(nz(fileUrl))).append("</FileURL>")
                .append("<Manufacturer>").append(escape(nz(manufacturer))).append("</Manufacturer>")
                .append("</Control>");
        client().sendMessage(channelId, sb.toString());
    }

    /**
     * 目标跟踪控制（2022）。
     *
     * <p>{@code Auto} 自动跟踪 / {@code Manual} 手动框选跟踪 / {@code Stop} 停止。
     * 手动模式需携带全景窗口与框选坐标。
     */
    public void targetTrack(String channelId, String targetTrack, String deviceId2,
                            Integer length, Integer width, Integer midpointX, Integer midpointY,
                            Integer lengthX, Integer lengthY) {
        String mode = targetTrack == null ? "Stop" : targetTrack.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>TargetTrack</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<TargetTrack>").append(mode).append("</TargetTrack>");
        if (deviceId2 != null && !deviceId2.isBlank()) {
            sb.append("<DeviceID2>").append(deviceId2).append("</DeviceID2>");
        }
        if ("Manual".equalsIgnoreCase(mode)) {
            sb.append("<Length>").append(nz(length)).append("</Length>")
                    .append("<Width>").append(nz(width)).append("</Width>")
                    .append("<MidpointX>").append(nz(midpointX)).append("</MidpointX>")
                    .append("<MidpointY>").append(nz(midpointY)).append("</MidpointY>")
                    .append("<LengthX>").append(nz(lengthX)).append("</LengthX>")
                    .append("<LengthY>").append(nz(lengthY)).append("</LengthY>");
        }
        sb.append("</Control>");
        client().sendMessage(channelId, sb.toString());
    }

    // ==================== 报警通知 / 喊话 ====================

    /** 报警通知下发（标准 A.2.5.9）。 */
    public void alarmNotify(String channelId, Integer priority, Integer method, Integer type,
                            String description, Double longitude, Double latitude) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Notify><CmdType>Alarm</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<AlarmPriority>").append(priority == null ? 1 : priority).append("</AlarmPriority>")
                .append("<AlarmMethod>").append(method == null ? 5 : method).append("</AlarmMethod>")
                .append("<AlarmTime>").append(TimeFormats.toIso(LocalDateTime.now())).append("</AlarmTime>");
        if (type != null) {
            sb.append("<AlarmType>").append(type).append("</AlarmType>");
        }
        if (description != null && !description.isBlank()) {
            sb.append("<AlarmDescription>").append(escape(description)).append("</AlarmDescription>");
        }
        if (longitude != null) {
            sb.append("<Longitude>").append(longitude).append("</Longitude>");
        }
        if (latitude != null) {
            sb.append("<Latitude>").append(latitude).append("</Latitude>");
        }
        sb.append("</Notify>");
        client().sendMessage(channelId, sb.toString());
    }

    /**
     * 语音喊话（标准 A.2.3.1.11 Broadcast）。
     *
     * <p>平台先发 {@code Broadcast} 通知，设备回 INVITE（{@code s=AudioBroadcast}），
     * 媒体侧由 {@code BroadcastService} 建立下行通道；音频数据通过 WebSocket 持续推送。
     */
    public void talk(String channelId, String sourceId) {
        client().sendMessage(channelId, ManscdpBuilder.broadcast(
                client().nextSn(), sourceId, channelId));
        log.info("已发送语音广播通知: source={} target={}", sourceId, channelId);
    }

    // ==================== 内部 ====================

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toLowerCase();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
