package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.PtzEncoder;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.service.ControlAckService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.Gb28181SipClientProvider;
import io.github.rurulalanana.starter.vo.ControlResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 国标（GB/T 28181—2022 附录 A.2.3）控制实现：{@link DeviceCapability} 里的
 * <b>全部 30 项</b>都覆写。
 *
 * <p>国标是平台的主链路，所以这里是「完整实现」，厂商实现只做覆写。
 * 逻辑与改造前的 {@code ControlService} 完全相同，只是去掉了
 * {@code providerOf(...) != null} 的分支——分流改由
 * {@link DeviceControlResolver} 在入口处选实现，本类不再关心别的协议。
 *
 * <h3>寻址：设备编码 vs 通道编码</h3>
 * 国标里控制命令的 {@code <DeviceID>} 是<b>通道编码</b>，而 SIP 的
 * {@code To}/{@code From}/请求行必须是<b>设备编码</b>。底层
 * {@code Gb28181SipClient} 只收一个 ID，两者不同时必然报
 * 「设备未注册或会话已失效」（直连摄像机二者相同所以能跑通，级联场景暴露）。
 * {@link #target} 负责拆成 {@link Target} 对。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Gb28181DeviceControl implements DeviceControl {

    /** 直连设备的默认云台地址。 */
    private static final int DEFAULT_ADDRESS = 1;
    /** 辅助开关编号：雨刷固定为 1（标准未定义，业界惯例）。 */
    private static final int WIPER_SWITCH_ID = 1;

    /**
     * 等设备回 MANSCDP 应答的时长（毫秒）。
     *
     * <p>取 2.5 秒的取舍：局域网设备实测 50~300ms 就回，给足余量覆盖慢固件；
     * 但多数设备对云台类命令<b>根本不做应用层应答</b>，等太久接口就变成
     * 「点一下卡三秒」。宁可如实报「设备未应答」，也不让界面假成功。
     */
    private static final int CONTROL_ACK_TIMEOUT_MS = 2500;

    /** 校时格式必须严格 {@code yyyy-MM-dd'T'HH:mm:ss}：带毫秒或时区后缀会被设备判非法。 */
    private static final DateTimeFormatter TIME_SYNC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Gb28181SipClientProvider clientProvider;
    private final DeviceService deviceService;
    /** 控制应答等待（A.2.6.2）。 */
    private final ControlAckService ackService;

    @Override
    public DeviceKind kind() {
        return DeviceKind.GB28181;
    }

    @Override
    public EnumSet<DeviceCapability> capabilities() {
        // 国标覆盖全部能力：漏一项就会让国标设备莫名其妙报「不支持」，
        // 且编译期不报错 —— 由 DeviceControlCapabilityTest 兜
        return EnumSet.allOf(DeviceCapability.class);
    }

    // ==================== 寻址 ====================

    /**
     * 一次控制指令的寻址对：{@code sipTarget} 用于 SIP 找会话与构造请求行，
     * {@code deviceId} 写进 MANSCDP 的 {@code <DeviceID>}。
     */
    private record Target(String sipTarget, String deviceId) {
    }

    /**
     * 解析寻址对：能查到通道则 SIP 目标用其所属设备编码，
     * 查不到则两者都退回 {@code channelId}（与旧行为一致）。
     */
    private Target target(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String deviceId = deviceService.deviceIdOfChannel(channelId);
        return new Target(deviceId == null ? channelId : deviceId, channelId);
    }

    private Gb28181SipClient client() {
        Gb28181SipClient c = clientProvider.client();
        if (c == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法下发控制命令");
        }
        return c;
    }

    /** 下发一条 MANSCDP 控制消息：XML 用通道编码构造，SIP 用设备编码寻址。 */
    private void sendControl(String channelId, Function<String, String> xmlBuilder) {
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), xmlBuilder.apply(t.deviceId()));
    }

    /**
     * 下发一条控制消息并等设备应答（A.2.6.2）。
     *
     * <p>只给「成败对用户有意义」的命令用：目标跟踪、拉框缩放、设备升级。
     * 云台方向键仍走发送即返回 —— 每个方向停一次都等 2.5 秒会把操作拖死。
     */
    private ControlResultVo sendControlAndAwait(String channelId,
                                                IntFunction<String> xmlBuilder) {
        Target t = target(channelId);
        Gb28181SipClient c = client();
        int sn = c.nextSn();
        // 先登记再发：设备在局域网里几十毫秒就回，先发后登记会漏掉这条应答
        CompletableFuture<ControlAckService.Ack> future = ackService.expect(t.deviceId(), sn);
        c.sendMessage(t.sipTarget(), xmlBuilder.apply(sn));
        ControlAckService.Ack ack = ackService.await(t.deviceId(), sn, future, CONTROL_ACK_TIMEOUT_MS);
        if (ack == null) {
            log.info("控制命令设备未应答: channel={} sn={}", t.deviceId(), sn);
            return ControlResultVo.noAnswer(sn, "命令已下发，设备在 "
                    + CONTROL_ACK_TIMEOUT_MS + "ms 内未回应答（多数设备对控制命令不做应用层应答）");
        }
        log.info("控制命令设备已应答: channel={} sn={} result={}", t.deviceId(), sn, ack.result());
        return ControlResultVo.answered(sn, ack.result());
    }

    /** 下发一条 PTZ 指令（PTZCmd 字节串）。 */
    private void sendPtz(String channelId, String ptzCmd) {
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(),
                ManscdpBuilder.ptzControl(client().nextSn(), t.deviceId(), ptzCmd));
    }

    // ==================== 云台 ====================

    @Override
    public void ptz(Ptz cmd) {
        String command = normalize(cmd.command());
        int speed = cmd.speed() == null ? 129 : Math.max(0, Math.min(255, cmd.speed()));
        if ("stop".equals(command)) {
            sendPtz(cmd.channelId(), PtzEncoder.stop(DEFAULT_ADDRESS));
            return;
        }
        // 变倍速度是 0~15 的独立字段，与方向速度不同量纲
        int zoomSpeed = Math.max(1, speed * 15 / 255);
        int bits = switch (command) {
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
            default -> throw BizException.badRequest("不支持的云台指令：" + cmd.command());
        };
        boolean zooming = bits == PtzEncoder.ZOOM_IN || bits == PtzEncoder.ZOOM_OUT;
        // 只变倍时方向速度填 0（填了会让某些设备同时开始转）
        if (zooming) {
            sendPtz(cmd.channelId(), PtzEncoder.direction(DEFAULT_ADDRESS, bits, 0, zoomSpeed));
        } else {
            sendPtz(cmd.channelId(), PtzEncoder.direction(DEFAULT_ADDRESS, bits, speed));
        }
    }

    /**
     * 焦点光圈控制（附录 A.3.1）：聚焦与光圈是以 {@code 0x40} 为基的独立指令码：
     * {@code 0x42} 聚焦近、{@code 0x41} 聚焦远、{@code 0x44} 光圈放大、{@code 0x48} 光圈缩小。
     *
     * <p>早期把它们映射到 {@code ZOOM_IN}/{@code ZOOM_OUT}，下发字节与变倍逐字节相同，
     * 现场表现是「按光圈，画面缩放」。
     */
    @Override
    public void focusIris(FocusIris cmd) {
        String command = normalize(cmd.command());
        int speed = cmd.speed() == null ? 129 : Math.max(0, Math.min(255, cmd.speed()));
        switch (command) {
            case "stop" -> sendPtz(cmd.channelId(), PtzEncoder.stop(DEFAULT_ADDRESS));
            case "irisin" -> sendPtz(cmd.channelId(),
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.IRIS_IN, 0, speed));
            case "irisout" -> sendPtz(cmd.channelId(),
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.IRIS_OUT, 0, speed));
            case "focusnear" -> sendPtz(cmd.channelId(),
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.FOCUS_NEAR, speed, 0));
            case "focusfar" -> sendPtz(cmd.channelId(),
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.FOCUS_FAR, speed, 0));
            default -> throw BizException.badRequest("不支持的焦点光圈指令：" + cmd.command());
        }
    }

    @Override
    public void ptzPrecise(PtzPrecise cmd) {
        client().ptzPrecise(cmd.channelId(), cmd.pan(), cmd.tilt(), cmd.zoom());
    }

    /**
     * 拉框放大 / 缩小（A.2.3.1.8 / A.2.3.1.9）。
     *
     * <p>走 2022 版标准：独立 XML 元素 {@code <DragZoomIn>/<DragZoomOut>}，
     * 坐标为<b>像素值</b>。
     *
     * <p><b>不能走 PTZCmd</b>：A.3.2 的指令表里没有拉框指令码，早期业界流传的
     * 11 字节 {@code 0x81/0x82} 形式既不在标准里，还与预置位的
     * {@code PRESET_SET/PRESET_CALL} 同码 —— 设备按报文长度猜，猜错就变成
     * 「点拉框放大，预置位被覆盖」。
     */
    @Override
    public ControlResultVo dragZoom(DragZoom cmd) {
        return sendControlAndAwait(cmd.channelId(), sn -> cmd.zoomIn()
                ? ManscdpBuilder.dragZoomIn(sn, cmd.channelId(), cmd.length(), cmd.width(),
                cmd.midpointX(), cmd.midpointY(), cmd.lengthX(), cmd.lengthY())
                : ManscdpBuilder.dragZoomOut(sn, cmd.channelId(), cmd.length(), cmd.width(),
                cmd.midpointX(), cmd.midpointY(), cmd.lengthX(), cmd.lengthY()));
    }

    // ==================== 预置位 ====================

    @Override
    public void preset(Preset cmd) {
        if (cmd.preset() == null) {
            throw BizException.badRequest("缺少预置位编号");
        }
        int op = switch (normalize(cmd.command())) {
            case "set" -> PtzEncoder.PRESET_SET;
            case "goto", "call" -> PtzEncoder.PRESET_CALL;
            case "remove", "delete" -> PtzEncoder.PRESET_REMOVE;
            default -> throw BizException.badRequest("不支持的预置位指令：" + cmd.command());
        };
        sendPtz(cmd.channelId(), PtzEncoder.preset(DEFAULT_ADDRESS, op, cmd.preset()));
    }

    @Override
    public void queryPresets(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.presetQuery(client().nextSn(), deviceId));
    }

    // ==================== 巡航 / 看守位 ====================

    /**
     * 巡航控制：{@code add}/{@code remove} 用 {@code 0x84}/{@code 0x85}，
     * {@code setspeed}/{@code setstay} 用 {@code 0x86}/{@code 0x87}，{@code start} 用 {@code 0x88}；
     * {@code stop} 没有专属指令码，就是普通停止（字节 4 全 0）。
     */
    @Override
    public void cruise(Cruise cmd) {
        String command = normalize(cmd.command());
        int group = cmd.groupId() == null ? 1 : cmd.groupId();
        switch (command) {
            case "query" -> sendControl(cmd.channelId(),
                    deviceId -> ManscdpBuilder.cruiseTrackListQuery(client().nextSn(), deviceId));
            case "add" -> sendPtz(cmd.channelId(), PtzEncoder.cruisePoint(
                    DEFAULT_ADDRESS, true, group, cmd.preset() == null ? 0 : cmd.preset()));
            case "remove" -> sendPtz(cmd.channelId(), PtzEncoder.cruisePoint(
                    DEFAULT_ADDRESS, false, group, cmd.preset() == null ? 0 : cmd.preset()));
            case "setspeed" -> sendPtz(cmd.channelId(), PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SPEED, group,
                    cmd.speed() == null ? 1 : cmd.speed()));
            case "setstay" -> sendPtz(cmd.channelId(), PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_TIME, group,
                    cmd.stay() == null ? 1 : cmd.stay()));
            case "start" -> sendPtz(cmd.channelId(), PtzEncoder.cruiseStart(DEFAULT_ADDRESS, group));
            case "stop" -> sendPtz(cmd.channelId(), PtzEncoder.stop(DEFAULT_ADDRESS));
            default -> throw BizException.badRequest("不支持的巡航指令：" + cmd.command());
        }
        log.info("巡航控制: channel={} command={} group={} preset={} speed={} stay={} name={}",
                cmd.channelId(), cmd.command(), cmd.groupId(), cmd.preset(),
                cmd.speed(), cmd.stay(), cmd.name());
    }

    @Override
    public void queryCruiseTrack(String channelId, Integer number) {
        sendControl(channelId, deviceId -> ManscdpBuilder.cruiseTrackQuery(
                client().nextSn(), deviceId, number == null ? 0 : number));
    }

    @Override
    public void queryHomePosition(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.homePositionQuery(client().nextSn(), deviceId));
    }

    /**
     * 看守位控制。它不是 PTZCmd：在 {@code <Control>} 下有自己的
     * {@code <HomePosition>} 元素，当成 PTZCmd 发设备不理。
     */
    @Override
    public void homePosition(HomePosition cmd) {
        boolean on = Boolean.TRUE.equals(cmd.enabled());
        int reset = cmd.resetTime() == null ? 0 : cmd.resetTime();
        int preset = cmd.presetIndex() == null ? 1 : cmd.presetIndex();
        Target t = target(cmd.channelId());
        client().sendMessage(t.sipTarget(), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Control><CmdType>DeviceControl</CmdType>"
                + "<SN>" + client().nextSn() + "</SN>"
                + "<DeviceID>" + t.deviceId() + "</DeviceID>"
                + "<HomePosition><Enabled>" + (on ? 1 : 0) + "</Enabled>"
                + (on ? "<ResetTime>" + reset + "</ResetTime>"
                + "<PresetIndex>" + preset + "</PresetIndex>" : "")
                + "</HomePosition></Control>");
    }

    // ==================== 辅助开关 ====================

    @Override
    public void wiper(String channelId, String command) {
        boolean on = "on".equals(normalize(command));
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, WIPER_SWITCH_ID, on));
    }

    @Override
    public void fillLight(String channelId, String command, Integer index) {
        boolean on = "on".equals(normalize(command));
        int idx = index == null ? 2 : index;
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, idx, on));
    }

    // ==================== 设备控制 ====================

    @Override
    public void recording(Recording cmd) {
        String value = switch (normalize(cmd.command())) {
            case "start" -> "Record";
            case "stop" -> "StopRecord";
            default -> throw BizException.badRequest("不支持的录像指令：" + cmd.command());
        };
        sendControl(cmd.channelId(), deviceId -> ManscdpBuilder.recordControl(
                client().nextSn(), deviceId, value, cmd.streamNumber()));
    }

    @Override
    public void guard(Arming cmd) {
        String value = switch (normalize(cmd.command())) {
            case "setguard" -> "SetGuard";
            case "resetguard" -> "ResetGuard";
            default -> throw BizException.badRequest("不支持的布防指令：" + cmd.command());
        };
        sendControl(cmd.channelId(),
                deviceId -> ManscdpBuilder.guardControl(client().nextSn(), deviceId, value));
    }

    /** 设备远程重启（A.2.3.1.3）。 */
    @Override
    public void reboot(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.teleBoot(client().nextSn(), deviceId));
    }

    /** 强制关键帧（A.2.3.1.7）。 */
    @Override
    public void forceIFrame(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.forceIFrame(client().nextSn(), deviceId));
    }

    /** 存储卡格式化（A.2.3.1.13）：{@code id=0} 表示全部。 */
    @Override
    public void formatSdCard(String channelId, Integer sdCardId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.formatSdCard(
                client().nextSn(), deviceId, sdCardId == null ? 0 : sdCardId));
    }

    /** 报警复位（A.2.3.1.9）。 */
    @Override
    public void alarmReset(String channelId) {
        sendControl(channelId, deviceId -> ManscdpBuilder.alarmReset(client().nextSn(), deviceId));
    }

    /**
     * 设备校时。设备时钟漂移会让录像 {@code StartTime/EndTime} 与平台检索区间对不上，
     * 用户查「昨天下午 3 点」什么都查不到而录像其实存在。
     */
    @Override
    public void syncTime(SyncTime cmd) {
        LocalDateTime time = cmd.time() == null ? LocalDateTime.now() : cmd.time();
        String text = time.format(TIME_SYNC_FORMAT);
        sendControl(cmd.channelId(), deviceId ->
                ManscdpBuilder.timeSync(client().nextSn(), deviceId, text));
        log.info("已下发校时: channel={} time={}", cmd.channelId(), text);
    }

    @Override
    public void deviceConfig(String channelId, String xml) {
        if (xml == null || xml.isBlank()) {
            throw BizException.badRequest("配置内容不能为空");
        }
        Target t = target(channelId);
        client().sendMessage(t.sipTarget(), xml);
    }

    @Override
    public void subscribe(Subscribe cmd) {
        String event = switch (normalize(cmd.type())) {
            case "catalog" -> "Catalog";
            case "alarm" -> "Alarm";
            case "position", "mobileposition" -> "MobilePosition";
            case "ptz" -> "PTZPosition";
            default -> throw BizException.badRequest("不支持的订阅类型：" + cmd.type());
        };
        int expires = cmd.expires() == null ? 3600 : cmd.expires();
        Target t = target(cmd.channelId());
        String body = switch (event) {
            case "MobilePosition" -> ManscdpBuilder.mobilePositionSubscribe(
                    client().nextSn(), t.deviceId(), cmd.interval());
            case "Catalog" -> ManscdpBuilder.catalogQuery(client().nextSn(), t.deviceId());
            default -> null;
        };
        client().subscribe(t.sipTarget(), event, expires, body);
        log.info("已发起订阅: channel={} sipTarget={} event={} expires={}",
                cmd.channelId(), t.sipTarget(), event, expires);
    }

    // ==================== 查询 ====================

    @Override
    public void queryDeviceStatus(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceStatusQuery(client().nextSn(), deviceId));
    }

    @Override
    public void queryDeviceInfo(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceInfoQuery(client().nextSn(), deviceId));
    }

    @Override
    public void queryConfig(String channelId, String configType) {
        sendControl(channelId, deviceId -> ManscdpBuilder.configDownloadQuery(
                client().nextSn(), deviceId, configType));
    }

    @Override
    public void querySdCardStatus(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.sdCardStatusQuery(client().nextSn(), deviceId));
    }

    @Override
    public void queryPtzPosition(String channelId) {
        sendControl(channelId,
                deviceId -> ManscdpBuilder.ptzPositionQuery(client().nextSn(), deviceId));
    }

    // ==================== 配置 / 升级 ====================

    @Override
    public void osdConfig(Osd cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceConfig</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(cmd.channelId()).append("</DeviceID>")
                .append("<OSDConfig>")
                .append("<Length>").append(nz(cmd.length())).append("</Length>")
                .append("<Width>").append(nz(cmd.width())).append("</Width>")
                .append("<TimeX>").append(nz(cmd.timeX())).append("</TimeX>")
                .append("<TimeY>").append(nz(cmd.timeY())).append("</TimeY>")
                .append("<TimeEnable>").append(nz(cmd.timeEnable())).append("</TimeEnable>")
                .append("<TimeType>").append(nz(cmd.timeType())).append("</TimeType>")
                .append("<TextEnable>").append(nz(cmd.textEnable())).append("</TextEnable>")
                .append("<SumNum>").append(cmd.text() == null || cmd.text().isBlank() ? 0 : 1)
                .append("</SumNum>");
        if (cmd.text() != null && !cmd.text().isBlank()) {
            sb.append("<Item><Text>").append(escape(cmd.text())).append("</Text>")
                    .append("<X>").append(nz(cmd.textX())).append("</X>")
                    .append("<Y>").append(nz(cmd.textY())).append("</Y></Item>");
        }
        sb.append("</OSDConfig></Control>");
        Target t = target(cmd.channelId());
        client().sendMessage(t.sipTarget(), sb.toString());
    }

    @Override
    public void snapshotConfig(Snapshot cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceConfig</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(cmd.channelId()).append("</DeviceID>")
                .append("<SnapShotConfig>")
                .append("<SnapNum>").append(cmd.snapNum() == null ? 1 : cmd.snapNum())
                .append("</SnapNum>")
                .append("<Interval>").append(cmd.interval() == null ? 1 : cmd.interval())
                .append("</Interval>");
        if (cmd.uploadUrl() != null && !cmd.uploadUrl().isBlank()) {
            sb.append("<UploadURL>").append(escape(cmd.uploadUrl())).append("</UploadURL>");
        }
        sb.append("<SessionID>").append(UUID.randomUUID().toString().replace("-", ""))
                .append("</SessionID>")
                .append("</SnapShotConfig></Control>");
        Target t = target(cmd.channelId());
        client().sendMessage(t.sipTarget(), sb.toString());
    }

    /**
     * 设备软件升级（A.2.3.1.12）。命令发出即返回，最终结果由设备另行通知。
     *
     * <p>这里等的是 A.2.6.2 的<b>控制应答</b>（受不受理），不是升级结果 ——
     * 升级要下载固件再刷写，几分钟到几十分钟都有可能，不能同步等。
     */
    @Override
    public ControlResultVo deviceUpgrade(Upgrade cmd) {
        // SessionID 由平台生成：32 位十六进制，正好落在标准要求的 32~128 字节区间，
        // 设备在结果通知里原样带回，用来关联本次升级
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        ControlResultVo vo = sendControlAndAwait(cmd.channelId(), sn -> ManscdpBuilder.deviceUpgrade(
                sn, cmd.channelId(), cmd.firmware(), cmd.fileUrl(),
                cmd.manufacturer(), sessionId));
        vo.setSessionId(sessionId);
        return vo;
    }

    /**
     * 目标跟踪控制（A.2.3.1.14）。
     *
     * <p>手动跟踪要让设备把框选位置换算成 PTZ 动作，6 个坐标缺一不可，
     * 少一个设备就算不出转向角度，所以这里直接拒掉而不是发一条残缺命令。
     */
    @Override
    public ControlResultVo targetTrack(TargetTrack cmd) {
        String mode = cmd.mode() == null ? "Stop" : cmd.mode().trim();
        if (!"Auto".equalsIgnoreCase(mode) && !"Manual".equalsIgnoreCase(mode)
                && !"Stop".equalsIgnoreCase(mode)) {
            throw BizException.badRequest("TargetTrack 取值必须是 Auto / Manual / Stop：" + mode);
        }
        if ("Manual".equalsIgnoreCase(mode)
                && (cmd.length() == null || cmd.width() == null || cmd.midpointX() == null
                || cmd.midpointY() == null || cmd.lengthX() == null || cmd.lengthY() == null)) {
            throw BizException.badRequest(
                    "手动跟踪需要完整框选参数：length / width / midpointx / midpointy / lengthx / lengthy");
        }
        return sendControlAndAwait(cmd.channelId(), sn -> ManscdpBuilder.targetTrack(
                sn, cmd.channelId(), mode, cmd.deviceId2(),
                cmd.length(), cmd.width(), cmd.midpointX(), cmd.midpointY(),
                cmd.lengthX(), cmd.lengthY()));
    }

    // ==================== 报警 / 喊话 ====================

    @Override
    public void alarmNotify(AlarmNotify cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Notify><CmdType>Alarm</CmdType>")
                .append("<SN>").append(client().nextSn()).append("</SN>")
                .append("<DeviceID>").append(cmd.channelId()).append("</DeviceID>")
                .append("<AlarmPriority>").append(cmd.priority() == null ? 1 : cmd.priority())
                .append("</AlarmPriority>")
                .append("<AlarmMethod>").append(cmd.method() == null ? 5 : cmd.method())
                .append("</AlarmMethod>")
                .append("<AlarmTime>").append(TimeFormats.toIso(LocalDateTime.now()))
                .append("</AlarmTime>");
        if (cmd.type() != null) {
            sb.append("<AlarmType>").append(cmd.type()).append("</AlarmType>");
        }
        if (cmd.description() != null && !cmd.description().isBlank()) {
            sb.append("<AlarmDescription>").append(escape(cmd.description()))
                    .append("</AlarmDescription>");
        }
        if (cmd.longitude() != null) {
            sb.append("<Longitude>").append(cmd.longitude()).append("</Longitude>");
        }
        if (cmd.latitude() != null) {
            sb.append("<Latitude>").append(cmd.latitude()).append("</Latitude>");
        }
        sb.append("</Notify>");
        Target t = target(cmd.channelId());
        client().sendMessage(t.sipTarget(), sb.toString());
    }

    /**
     * 语音喊话（A.2.3.1.11 Broadcast）：平台发通知，设备回 INVITE，
     * 媒体侧由 {@code BroadcastService} 建立下行通道，音频经 WebSocket 持续推送。
     */
    @Override
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

    private static String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
