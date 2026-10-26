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
    private final DeviceService deviceService;

    private Gb28181SipClient client() {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法下发控制命令");
        }
        return client;
    }

    // ==================== 寻址：设备编码 vs 通道编码 ====================

    /**
     * 一次控制指令的寻址对：{@code sipTarget} 用于 SIP 层找会话与构造请求行，
     * {@code deviceId} 是写进 MANSCDP {@code <DeviceID>} 的值。
     *
     * <p><b>为什么必须分开</b>：GB/T 28181 里云台、预置位、录像等控制命令的
     * {@code <DeviceID>} 是<b>通道编码</b>（功能挂在通道上），而 SIP 的
     * {@code To}/{@code From}/请求行必须用<b>设备编码</b>（设备才是注册实体）。
     * 底层 {@code Gb28181SipClient} 的方法只收一个 {@code deviceId} 并同时用于
     * 这两处，于是「设备编码 ≠ 通道编码」时必然失败：
     * <pre>
     * 直连摄像机：设备 34020000001310000001 / 通道 34020000001310000001 → 恰好相同，能跑通
     * 下级平台：  设备 34020000002000000006 / 通道 34020000001310000002 → 报「设备未注册或会话已失效」
     * </pre>
     * 因此本服务统一用本记录分离两者：SIP 层用 {@code sipTarget}，
     * MANSCDP 用 {@code deviceId}。
     */
    private record Target(String sipTarget, String deviceId) {
    }

    /**
     * 解析控制指令的寻址对。
     *
     * <p>{@code channelId} 是业务侧传入的通道编码。若库中能查到该通道，
     * 则 SIP 目标取其所属设备编码；查不到（如直接传设备编码、或通道尚未同步）
     * 则两者都退回 {@code channelId}，保持与旧行为一致。
     */
    private Target target(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String deviceId = deviceService.deviceIdOfChannel(channelId);
        return new Target(deviceId == null ? channelId : deviceId, channelId);
    }

    /**
     * 下发一条 MANSCDP 控制消息。
     *
     * <p>XML 由 {@code xmlBuilder} 用<b>通道编码</b>构造（保证 {@code <DeviceID>}
     * 符合规范），SIP 层用<b>设备编码</b>寻址。
     */
    private void sendControl(String channelId, java.util.function.Function<String, String> xmlBuilder) {
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), xmlBuilder.apply(t.deviceId()));
    }

    /**
     * 下发一条 PTZ 指令。
     *
     * <p>PTZ 命令码由 {@link PtzEncoder} 生成，与 {@code <DeviceID>} 无关，
     * 因此只需保证 SIP 层寻址正确、{@code <DeviceID>} 写通道编码。
     */
    private void sendPtz(String channelId, String ptzCmd) {
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(),
                ManscdpBuilder.ptzControl(client().nextSn(), t.deviceId(), ptzCmd));
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
            sendPtz(channelId, PtzEncoder.stop(DEFAULT_ADDRESS));
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
        sendPtz(channelId, PtzEncoder.direction(DEFAULT_ADDRESS, bits, sp));
    }

    /**
     * 焦点光圈控制。
     *
     * <h3>为什么现在只支持 {@code stop}</h3>
     * GB/T 28181 附录 A.3 的<b>指令码里没有光圈位</b>，也没有聚焦位 ——
     * 那一字节只有：{@code 0x01} 右、{@code 0x02} 左、{@code 0x04} 下、
     * {@code 0x08} 上、{@code 0x10} 变倍放大、{@code 0x20} 变倍缩小，
     * 以及 {@code 0x40} 起的预置位/巡航模式。
     *
     * <p>此前这里把 {@code irisin}/{@code focusfar} 映射到 {@link PtzEncoder#ZOOM_IN}、
     * {@code irisout}/{@code focusnear} 映射到 {@link PtzEncoder#ZOOM_OUT}，
     * 注释还写着「标准中焦点与光圈复用变倍位」—— 那是错的。
     * 下发出去的字节与「变倍放大/缩小」<b>逐字节相同</b>，
     * 设备当然按标准当变倍执行，现场表现就是「按光圈，画面缩放」。
     *
     * <p>现在改为明确报错：留一个静默走错动作的接口，比没有这个功能更危险 ——
     * 用户会反复重试、运维会去查设备光圈模块。
     *
     * <p><b>确实需要光圈的场景</b>：只能走厂商私有 PTZ 指令（每个品牌不同，
     * 与设备型号强相关），或给平台接一套 ONVIF 客户端。两条路都不属于
     * GB/T 28181 范畴，不在本方法里硬编码。
     *
     * @throws BizException {@code command} 不是 {@code stop} 时
     */
    public void focusIris(String channelId, String command, Integer speed) {
        String cmd = normalize(command);
        if (!"stop".equals(cmd)) {
            throw BizException.badRequest(
                    "GB/T 28181 的 PTZ 指令码不含光圈/聚焦位，无法下发「" + command
                            + "」。光圈只能通过厂商私有指令或 ONVIF 控制。");
        }
        sendPtz(channelId, PtzEncoder.stop(DEFAULT_ADDRESS));
    }

    /** 精确云台控制（2022）：角度 + 变焦倍数。 */
    public void ptzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
        client().ptzPrecise(channelId, pan, tilt, zoom);
    }

    /** 拉框放大 / 缩小。 */
    public void dragZoom(String channelId, boolean zoomIn, int length, int width,
                         int midpointX, int midpointY, int lengthX, int lengthY) {
        sendPtz(channelId, PtzEncoder.dragZoom(zoomIn, length, width,
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
        sendPtz(channelId, PtzEncoder.preset(DEFAULT_ADDRESS, op, preset));
    }

    /** 查询设备侧预置位列表。 */
    public void queryPresets(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.presetQuery(client().nextSn(), deviceId));
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
            case "query" -> sendControl(channelId,
                    deviceId -> ManscdpBuilder.cruiseTrackListQuery(client().nextSn(), deviceId));
            case "add", "remove" -> sendPtz(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group,
                            preset == null ? 0 : preset));
            case "setspeed" -> sendPtz(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group, speed == null ? 1 : speed));
            case "setstay" -> sendPtz(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SET, group, stay == null ? 1 : stay));
            case "start" -> sendPtz(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_START, group, 0));
            case "stop" -> sendPtz(channelId,
                    PtzEncoder.cruise(DEFAULT_ADDRESS, PtzEncoder.CRUISE_STOP, group, 0));
            default -> throw BizException.badRequest("不支持的巡航指令：" + command);
        }
        log.info("巡航控制: channel={} command={} group={} preset={} speed={} stay={} name={}",
                channelId, command, groupId, preset, speed, stay, name);
    }

    /** 查询巡航轨迹。 */
    public void queryCruiseTrack(String channelId, Integer number) {
        sendControl(channelId, deviceId -> ManscdpBuilder.cruiseTrackQuery(
                client().nextSn(), deviceId, number == null ? 0 : number));
    }

    /** 查询看守位信息。 */
    public void queryHomePosition(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.homePositionQuery(client().nextSn(), deviceId));
    }

    /** 看守位控制。 */
    public void homePosition(String channelId, Boolean enabled, Integer resetTime, Integer presetIndex) {
        sendPtz(channelId, PtzEncoder.watchdog(DEFAULT_ADDRESS,
                Boolean.TRUE.equals(enabled),
                resetTime == null ? 0 : resetTime,
                presetIndex == null ? 1 : presetIndex));
    }

    // ==================== 辅助开关 ====================

    /** 雨刷开关。 */
    public void wiper(String channelId, String command) {
        boolean on = "on".equals(normalize(command));
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, 0, on));
    }

    /** 补光灯开关。 */
    public void fillLight(String channelId, String command, Integer index) {
        boolean on = "on".equals(normalize(command));
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS,
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
        sendControl(channelId, deviceId -> ManscdpBuilder.recordControl(
                client().nextSn(), deviceId, value, streamNumber));
    }

    /** 布防 / 撤防。 */
    public void guard(String channelId, String command) {
        String value = switch (normalize(command)) {
            case "setguard" -> "SetGuard";
            case "resetguard" -> "ResetGuard";
            default -> throw BizException.badRequest("不支持的布防指令：" + command);
        };
        sendControl(channelId,
                deviceId -> ManscdpBuilder.guardControl(client().nextSn(), deviceId, value));
    }

    /** 设备远程重启（标准 A.2.3.1.3）。 */
    public void teleBoot(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.teleBoot(client().nextSn(), deviceId));
    }

    /** 强制关键帧（标准 A.2.3.1.7）。 */
    public void forceIFrame(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.forceIFrame(client().nextSn(), deviceId));
    }

    /** 存储卡格式化（标准 A.2.3.1.13）：{@code id=0} 表示全部。 */
    public void formatSdCard(String channelId, Integer sdCardId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.formatSdCard(
                client().nextSn(), deviceId, sdCardId == null ? 0 : sdCardId));
    }

    /** 报警复位（标准 A.2.3.1.9）。 */
    public void alarmReset(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.alarmReset(client().nextSn(), deviceId));
    }

    /**
     * 设备校时（标准 A.2.3.1.9 / 9.12）。
     *
     * <p>把平台当前时间下发给设备。设备时钟漂移会让它上报的录像
     * {@code StartTime/EndTime} 与平台检索区间对不上 ——
     * 用户按「昨天下午 3 点」查录像会什么都查不到，而录像其实存在。
     * 这类问题排查成本极高（看起来像设备没录像），所以提供手动校时入口，
     * 也便于定时任务批量校准。
     *
     * @param channelId 通道编码；内部会换算成 SIP 寻址用的设备编码
     * @param localTime 要下发的本地时间；{@code null} 表示用平台当前时间
     */
    public void syncTime(String channelId, java.time.LocalDateTime localTime) {
        java.time.LocalDateTime t = localTime == null
                ? java.time.LocalDateTime.now() : localTime;
        // 格式严格：yyyy-MM-dd'T'HH:mm:ss。带毫秒或时区后缀会被设备判非法
        String text = t.format(java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        sendControl(channelId, deviceId ->
                ManscdpBuilder.timeSync(client().nextSn(), deviceId, text));
        log.info("已下发校时: channel={} time={}", channelId, text);
    }

    /** 设备配置下发（2022）：直接透传 xml（由调用方按标准拼装配置段）。 */
    public void deviceConfig(String channelId, String xml) {
        if (xml == null || xml.isBlank()) {
            throw BizException.badRequest("配置内容不能为空");
        }
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), xml);
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
        Target t = target(channelId);
        String body = switch (event) {
            case "MobilePosition" -> ManscdpBuilder.mobilePositionSubscribe(
                    client().nextSn(), t.deviceId(), interval);
            case "Catalog" -> ManscdpBuilder.catalogQuery(client().nextSn(), t.deviceId());
            default -> null;
        };
        client().subscribe(t.sipTarget(), event, exp, body);
        log.info("已发起订阅: channel={} sipTarget={} event={} expires={}",
                channelId, t.sipTarget(), event, exp);
    }

    /** 查询设备状态（标准 9.6）。 */
    public void queryDeviceStatus(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceStatusQuery(client().nextSn(), deviceId));
    }

    /** 查询设备信息（标准 9.5）。 */
    public void queryDeviceInfo(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceInfoQuery(client().nextSn(), deviceId));
    }

    /** 查询设备配置（标准 A.2.4.7）。 */
    public void queryConfig(String channelId, String configType) {
        sendControl(channelId, deviceId -> ManscdpBuilder.configDownloadQuery(
                client().nextSn(), deviceId, configType));
    }

    /** 查询存储卡状态（2022）。 */
    public void querySdCardStatus(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.sdCardStatusQuery(client().nextSn(), deviceId));
    }

    /** 查询 PTZ 精确状态（2022）。 */
    public void queryPtzPosition(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.ptzPositionQuery(client().nextSn(), deviceId));
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
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), sb.toString());
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
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), sb.toString());
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
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), sb.toString());
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
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), sb.toString());
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
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), sb.toString());
    }

    /**
     * 语音喊话（标准 A.2.3.1.11 Broadcast）。
     *
     * <p>平台先发 {@code Broadcast} 通知，设备回 INVITE（{@code s=AudioBroadcast}），
     * 媒体侧由 {@code BroadcastService} 建立下行通道；音频数据通过 WebSocket 持续推送。
     */
    public void talk(String channelId, String sourceId) {
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), ManscdpBuilder.broadcast(
                client().nextSn(), sourceId, t.deviceId()));
        log.info("已发送语音广播通知: source={} sipTarget={} target={}",
                sourceId, t.sipTarget(), t.deviceId());
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
