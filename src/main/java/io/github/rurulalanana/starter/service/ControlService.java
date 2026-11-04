package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.PtzEncoder;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.vo.ControlResultVo;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;

import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 设备控制服务（GB/T 28181—2022 附录 A.2.3 Control）。覆盖云台、焦点光圈、预置位、巡航、看守位、
 * 录像控制、布撤防、重启、关键帧、格式化、报警复位、拉框缩放、雨刷补光、OSD、抓拍、升级、订阅、喊话。
 * PTZ 编码见 {@link PtzEncoder}（依赖模块的 {@code PtzCommand} 无法表达预置位编号 1~255）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlService {

    /** 直连设备的默认云台地址。 */
    private static final int DEFAULT_ADDRESS = 1;
    /** 辅助开关编号：雨刷固定为 1（标准未定义，业界惯例）。 */
    private static final int WIPER_SWITCH_ID = 1;
    /**
     * 转给厂商 SDK 的云台速度（0~255 口径）。取中值 129 而非满速：
     * SDK 方向控制是连续动作，满速下轻微超调就会把机位带到画面外；国标侧默认也是 129。
     */
    private static final int PTZ_SDK_SPEED = 129;

    /**
     * 等设备回 MANSCDP 应答的时长（毫秒）。
     *
     * <p>取 2.5 秒的取舍：局域网设备实测 50~300ms 就回，给足余量覆盖慢固件；
     * 但多数设备对云台类命令<b>根本不做应用层应答</b>，等太久接口就变成「点一下卡三秒」。
     * 宁可如实报「设备未应答」，也不让界面假成功。
     */
    private static final int CONTROL_ACK_TIMEOUT_MS = 2500;

    private final Gb28181SipClientProvider clientProvider;
    private final DeviceService deviceService;
    /** 非国标设备的接入实现（大华 SDK 等）；国标设备不走这里。 */
    private final DeviceAccessRegistry accessRegistry;
    /** 控制应答等待（A.2.6.2）。 */
    private final ControlAckService ackService;
    /** SDK 直连设备的预置位/巡航等扩展能力。 */
    private final DahuaCapabilityService dahuaCapability;
    /** SDK 直连设备的录像计划（国标 Record/StopRecord 的等价实现）。 */
    private final DahuaRecordPlanService dahuaRecordPlan;
    /** SDK 直连设备的配置项开关（布撤防等）。 */
    private final DahuaConfigEditService dahuaConfigEdit;

    private Gb28181SipClient client() {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法下发控制命令");
        }
        return client;
    }

    // ==================== 寻址：设备编码 vs 通道编码 ====================

    /**
     * 一次控制指令的寻址对：{@code sipTarget} 用于 SIP 找会话与构造请求行，
     * {@code deviceId} 写进 MANSCDP 的 {@code <DeviceID>}。
     * 国标里控制命令的 {@code <DeviceID>} 是通道编码，而 SIP 的 {@code To}/{@code From}/请求行必须是设备编码；
     * 底层 {@code Gb28181SipClient} 只收一个 ID，两者不同时必然报「设备未注册或会话已失效」
     * （直连摄像机二者相同所以能跑通，级联场景暴露）。
     */
    private record Target(String sipTarget, String deviceId) {
    }

    /**
     * 解析控制指令的寻址对：能查到通道则 SIP 目标用其所属设备编码，
     * 查不到则两者都退回 {@code channelId}（与旧行为一致）。
     */
    private Target target(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String deviceId = deviceService.deviceIdOfChannel(channelId);
        return new Target(deviceId == null ? channelId : deviceId, channelId);
    }

    /**
     * 下发一条 MANSCDP 控制消息：XML 用通道编码构造，SIP 用设备编码寻址。
     * 这是唯一的下行出口，非国标设备的分流只在这里判一次；
     * 放到各 public 方法里会让新增命令时必然遗漏。
     */
    private void sendControl(String channelId, java.util.function.Function<String, String> xmlBuilder) {
        Target t = target(channelId);
        // 非国标设备没有 MANSCDP，交回对应 provider 自己处理
        DeviceAccessProvider p = providerOf(t.sipTarget());
        if (p != null) {
            // 目前只有云台类命令在 SDK 侧有实现；其余命令显式拒绝而不是静默丢弃（静默失败会让人以为「点了没反应」）
            throw BizException.badRequest("该设备通过 " + p.kind().code()
                    + " 接入，此控制命令暂未适配；请在设备本体上操作");
        }
        client().sendMessage(t.sipTarget(), xmlBuilder.apply(t.deviceId()));
    }

    /**
     * 下发一条控制消息并等设备应答（A.2.6.2）。
     *
     * <p>与 {@link #sendControl} 的唯一区别是<b>拿得到设备的回话</b>。
     * 只给「成败对用户有意义」的命令用：目标跟踪、拉框缩放、设备升级。
     * 云台方向键仍走发送即返回 —— 每个方向停一次都等 2.5 秒会把操作拖死。
     *
     * @param xmlBuilder SN → 完整 MANSCDP 报文；SN 由本方法生成以保证能对上应答
     */
    private ControlResultVo sendControlAndAwait(String channelId,
                                                java.util.function.IntFunction<String> xmlBuilder) {
        Target t = target(channelId);
        DeviceAccessProvider p = providerOf(t.sipTarget());
        if (p != null) {
            throw BizException.badRequest("该设备通过 " + p.kind().code()
                    + " 接入，此控制命令暂未适配；请在设备本体上操作");
        }
        Gb28181SipClient c = client();
        int sn = c.nextSn();
        // 先登记再发：设备在局域网里几十毫秒就回，先发后登记会漏掉这条应答
        CompletableFuture<ControlAckService.Ack> future = ackService.expect(t.deviceId(), sn);
        c.sendMessage(t.sipTarget(), xmlBuilder.apply(sn));
        ControlAckService.Ack ack = ackService.await(t.deviceId(), sn, future, CONTROL_ACK_TIMEOUT_MS);
        if (ack == null) {
            log.info("控制命令设备未应答: channel={} sn={}", t.deviceId(), sn);
            return ControlResultVo.noAnswer(sn,
                    "命令已下发，设备在 " + CONTROL_ACK_TIMEOUT_MS + "ms 内未回应答（多数设备对控制命令不做应用层应答）");
        }
        log.info("控制命令设备已应答: channel={} sn={} result={}", t.deviceId(), sn, ack.result());
        return ControlResultVo.answered(sn, ack.result());
    }

    /**
     * 下发一条 PTZ 指令。非国标设备（大华 SDK）在这里分流：SDK 的方向控制是独立 API，
     * 没有 PTZCmd 字节串的概念。
     */
    private void sendPtz(String channelId, String ptzCmd) {
        Target t = target(channelId);
        DeviceAccessProvider p = providerOf(t.sipTarget());
        if (p != null) {
            String dir = ptzCmdToSdkCommand(ptzCmd);
            if (dir == null) {
                /*
                 * 这个指令码不在 SDK 的可映射范围（预置位 / 巡航 / 扫描 / 辅助开关）。
                 * 以前会静默退化成一次方向转动，用户点「设置预置位」结果镜头转走了。
                 * 明说不可用，比动一下更有用。
                 */
                throw BizException.badRequest("该设备通过 " + p.kind().code()
                        + " 接入，此云台指令暂未适配（只支持方向、变倍、聚焦、光圈）；"
                        + "预置位/巡航请在设备本体或国标通道上操作");
            }
            try {
                p.ptz(contextOf(t.sipTarget()), t.deviceId(), dir, PTZ_SDK_SPEED);
            } catch (IllegalStateException | IllegalArgumentException e) {
                /*
                 * 转成 400：设备侧拒绝（不是球机 / 通道号错 / 会话失效）是正常的业务失败，
                 * 不该按服务端故障打 ERROR + 堆栈。provider 抛的消息已经带了错误码与原因。
                 */
                throw BizException.badRequest(e.getMessage() == null
                        ? "云台指令下发失败" : e.getMessage());
            }
            return;
        }
        client().sendMessage(t.sipTarget(),
                ManscdpBuilder.ptzControl(client().nextSn(), t.deviceId(), ptzCmd));
    }

    /**
     * 取该设备对应的接入实现；国标设备返回 {@code null}。
     * 先按设备编码查，查不到再按通道反查 —— 与 {@link #target} 的回退口径一致，
     * 否则「通道编码传进来」的场景会被误判成国标设备而往 SIP 里发。
     */
    private DeviceAccessProvider providerOf(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            String owner = deviceService.deviceIdOfChannel(deviceId);
            device = owner == null ? null : deviceService.findDevice(owner);
        }
        if (device == null) {
            return null;
        }
        DeviceKind kind = DeviceKind.of(device.getSource());
        return accessRegistry.provider(kind).orElse(null);
    }

    /** 设备记录 → 接入上下文（不打印密码，见 {@link DeviceContext#toString()}）。 */
    private DeviceContext contextOf(String deviceId) {
        GbDevice d = deviceService.findDevice(deviceId);
        if (d == null) {
            String owner = deviceService.deviceIdOfChannel(deviceId);
            d = owner == null ? null : deviceService.findDevice(owner);
        }
        if (d == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        return new DeviceContext(d.getDeviceId(), d.getRemoteIp(),
                d.getRemotePort() == null ? 0 : d.getRemotePort(),
                d.getUsername(), d.getPassword(),
                d.getChannelCount() == null ? 0 : d.getChannelCount());
    }

    /**
     * 把国标 PTZ 指令码翻译成「协议无关的方向词」，供厂商 SDK 使用。
     *
     * <p><b>必须按指令族整体匹配，不能只做方向位与运算</b>。
     * {@link PtzEncoder} 的字节4 里，方向只占 {@code 0x01}~{@code 0x08}，
     * 但聚焦/光圈的码值是 {@code 0x41}/{@code 0x42}/{@code 0x44}/{@code 0x48}
     * —— 它们的低位恰好与方向位重合，于是：
     * <pre>
     *   0x41 聚焦远  &amp; 0x01(右) = 0x01  → 被判成「右」
     *   0x42 聚焦近  &amp; 0x02(左) = 0x02  → 被判成「左」
     *   0x44 光圈放大 &amp; 0x04(下) = 0x04  → 被判成「下」
     *   0x48 光圈缩小 &amp; 0x08(上) = 0x08  → 被判成「上」
     * </pre>
     * 现场表现就是本次反馈的现象：<b>点光圈，云台上下动；点聚焦，云台左右动</b>。
     * 同类还有预置位 {@code 0x81}（低两位是 01）会被当成「右」。
     *
     * <p>因此这里按族划分：方向族 {@code 0x00~0x0F}、变倍 {@code 0x10}/{@code 0x20}、
     * 聚焦光圈四个定值；<b>其余一律返回 {@code null}</b>（预置位、巡航、扫描、
     * 辅助开关），由调用方明确报「SDK 侧不支持」，而不是悄悄变成一次转动。
     *
     * @return 方向词；{@code stop} 表示停止；无法映射时返回 {@code null}
     */
    static String ptzCmdToSdkCommand(String hex) {
        if (hex == null || hex.isBlank()) {
            return "stop";
        }
        int cmd;
        try {
            String h = hex.trim().replace("0x", "").replace(" ", "").replace("-", "");
            if (h.length() < 8) {
                return "stop";
            }
            cmd = Integer.parseInt(h.substring(6, 8), 16);
        } catch (RuntimeException e) {
            // 解析不了就按「停」—— 比随便猜一个方向安全
            log.debug("无法解析 PTZ 指令码，按 stop 处理: {}", hex);
            return "stop";
        }
        if (cmd == 0) {
            return "stop";
        }

        // ① 聚焦 / 光圈：以 0x40 为基的独立指令码，必须先于方向位判断
        switch (cmd) {
            case PtzEncoder.FOCUS_FAR:
                return "focusfar";
            case PtzEncoder.FOCUS_NEAR:
                return "focusnear";
            case PtzEncoder.IRIS_IN:
                return "irisin";
            case PtzEncoder.IRIS_OUT:
                return "irisout";
            default:
                break;
        }

        // ② 变倍：单一指令码，不与方向叠加（UI 也不会这么发）
        if (cmd == PtzEncoder.ZOOM_IN) {
            return "zoomin";
        }
        if (cmd == PtzEncoder.ZOOM_OUT) {
            return "zoomout";
        }

        // ③ 方向族：只有 0x00~0x0F 这一段是纯方向位，可叠加出斜向
        if (cmd > 0x0F) {
            // 预置位/巡航/扫描/辅助开关等：厂商 SDK 的 ptz() 没有对应入参，
            // 返回 null 让调用方报错，不要退化成一次方向转动
            log.debug("PTZ 指令码不在 SDK 可映射范围: 0x{}", Integer.toHexString(cmd));
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if ((cmd & PtzEncoder.UP) != 0) {
            sb.append("up");
        } else if ((cmd & PtzEncoder.DOWN) != 0) {
            sb.append("down");
        }
        if ((cmd & PtzEncoder.LEFT) != 0) {
            sb.append("left");
        } else if ((cmd & PtzEncoder.RIGHT) != 0) {
            sb.append("right");
        }
        return sb.length() == 0 ? "stop" : sb.toString();
    }

    // ==================== 云台 ====================

    /**
     * 云台方向控制。{@code speed} 语义随指令不同：方向是水平 / 垂直速度 0~255，
     * 变倍是 0~15（字节 7 高 4 位）；直接把 129 塞进变倍会被夹成 15，永远最大速。
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
        // 变倍速度是 0~15 的独立字段，与方向速度不同量纲
        int zoomSpeed = Math.max(1, sp * 15 / 255);
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
        boolean zooming = bits == PtzEncoder.ZOOM_IN || bits == PtzEncoder.ZOOM_OUT;
        // 只变倍时方向速度填 0（填了会让某些设备同时开始转）
        if (zooming) {
            sendPtz(channelId, PtzEncoder.direction(DEFAULT_ADDRESS, bits, 0, zoomSpeed));
        } else {
            sendPtz(channelId, PtzEncoder.direction(DEFAULT_ADDRESS, bits, sp));
        }
    }

    /**
     * 焦点光圈控制。GB/T 28181 附录 A.3.1 里聚焦与光圈是以 {@code 0x40} 为基的独立指令码：
     * {@code 0x42} 聚焦近、{@code 0x41} 聚焦远、{@code 0x44} 光圈放大、{@code 0x48} 光圈缩小。
     * 早期把它们映射到 {@code ZOOM_IN}/{@code ZOOM_OUT}，下发字节与变倍逐字节相同，
     * 现场表现是「按光圈，画面缩放」。
     */
    public void focusIris(String channelId, String command, Integer speed) {
        String cmd = normalize(command);
        int sp = speed == null ? 129 : Math.max(0, Math.min(255, speed));
        switch (cmd) {
            case "stop" -> sendPtz(channelId, PtzEncoder.stop(DEFAULT_ADDRESS));
            case "irisin" -> sendPtz(channelId,
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.IRIS_IN, 0, sp));
            case "irisout" -> sendPtz(channelId,
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.IRIS_OUT, 0, sp));
            case "focusnear" -> sendPtz(channelId,
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.FOCUS_NEAR, sp, 0));
            case "focusfar" -> sendPtz(channelId,
                    PtzEncoder.fi(DEFAULT_ADDRESS, PtzEncoder.FOCUS_FAR, sp, 0));
            default -> throw BizException.badRequest("不支持的焦点光圈指令：" + command);
        }
    }

    /** 精确云台控制（2022）：角度 + 变焦倍数。 */
    public void ptzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
        client().ptzPrecise(channelId, pan, tilt, zoom);
    }

    /**
     * 拉框放大 / 缩小（A.2.3.1.8 / A.2.3.1.9）。
     *
     * <p>走 2022 版标准：独立 XML 元素 {@code <DragZoomIn>/<DragZoomOut>}，
     * 坐标为<b>像素值</b>，{@code length}/{@code width} 是平台播放窗口的像素尺寸。
     *
     * <p><b>不能走 PTZCmd</b>：A.3.2 的指令表（表 A.4/A.5）里没有拉框指令码，
     * 早期业界流传的 11 字节 {@code 0x81/0x82} 形式既不在标准里，还与预置位的
     * {@code PRESET_SET/PRESET_CALL} 同码 —— 设备按报文长度猜，猜错就变成
     * 「点拉框放大，预置位被覆盖」。
     */
    public ControlResultVo dragZoom(String channelId, boolean zoomIn, int length, int width,
                                    int midpointX, int midpointY, int lengthX, int lengthY) {
        return sendControlAndAwait(channelId, sn -> zoomIn
                ? ManscdpBuilder.dragZoomIn(sn, channelId, length, width,
                midpointX, midpointY, lengthX, lengthY)
                : ManscdpBuilder.dragZoomOut(sn, channelId, length, width,
                midpointX, midpointY, lengthX, lengthY));
    }

    // ==================== 预置位 ====================

    /**
     * SDK 直连设备的预置位操作，<b>全部走设备</b>。
     *
     * <p>通道号要用 {@code sdkChannel}（IPC 是 0、NVR 是 1~N），
     * 与云台方向键同一口径 —— 用 1 去控 IPC 不报错但设备不动。
     *
     * <h3>为什么不再存平台坐标</h3>
     * 早期因为「{@code PtzPreset} 配置项读不到」误判成「该型号不支持预置点」，
     * 于是做了一套「平台存 (pan,tilt,zoom) + 绝对定位回位」的兜底。
     * 后来发现设备<b>完全支持</b>：`QueryDevState(0x57)` 能读到清单（含名称与坐标），
     * GOTO 精确回位（连做 3 次误差 0）。
     *
     * <p>兜底方案是多余的一层：<b>设备有的就用设备的</b>。
     * 它能做的设备都能做，还多出「设备 Web 界面可见、其他客户端可用、
     * 设备侧掉电不丢」这些平台坐标给不了的特性。留着只会带来
     * 「两边不同步」的维护成本和「设备上没有却显示存在」的假数据。
     *
     * <p>平台侧仍保留的是<b>预置点名称</b>（{@code gb_preset.name}）——
     * 那是设备不存、只有平台知道的信息，与「设备能力」无关。
     */
    private void presetBySdk(DeviceAccessProvider provider, Target t,
                             String command, int preset) {
        var session = dahuaCapability.requireSession(t.sipTarget());
        int sdkChannel = dahuaCapability.sdkChannel(session, t.sipTarget(), t.deviceId());
        var ext = dahuaCapability.ext();
        try {
            switch (normalize(command)) {
                case "set" -> ext.setPreset(session, sdkChannel, preset);
                case "goto", "call" -> ext.gotoPreset(session, sdkChannel, preset);
                case "remove", "delete" -> ext.deletePreset(session, sdkChannel, preset);
                default -> throw BizException.badRequest("不支持的预置位指令：" + command);
            }
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw dahuaCapability.translate("预置位操作", e);
        }
    }

    /** 预置位控制：{@code set} / {@code goto} / {@code remove}。 */
    public void preset(String channelId, String command, Integer preset) {
        if (preset == null) {
            throw BizException.badRequest("缺少预置位编号");
        }
        /*
         * SDK 直连设备的预置位必须走 SDK，不能编码成国标 PTZCmd：
         * 大华的预置位是 "PtzPreset" 配置项 + 专属指令码，收到国标的
         * 0x81/0x82/0x83 只会当成未知指令忽略（而 SDK 照样返回成功），
         * 表现为「点了设置预置位，界面说成功，设备上什么都没有」。
         */
        Target t = target(channelId);
        DeviceAccessProvider sdkProvider = providerOf(t.sipTarget());
        if (sdkProvider != null) {
            presetBySdk(sdkProvider, t, command, preset);
            return;
        }
        int op = switch (normalize(command)) {
            case "set" -> PtzEncoder.PRESET_SET;
            case "goto", "call" -> PtzEncoder.PRESET_CALL;
            case "remove", "delete" -> PtzEncoder.PRESET_REMOVE;
            default -> throw BizException.badRequest("不支持的预置位指令：" + command);
        };
        sendPtz(channelId, PtzEncoder.preset(DEFAULT_ADDRESS, op, preset));
    }

    /**
     * 查询设备侧预置位列表。
     *
     * <p><b>SDK 直连设备什么也不做，直接返回成功</b> —— 这不是偷懒：
     * 国标是「下发 {@code PresetQuery} → 等设备 MANSCDP 应答 → 落内存快照」，
     * 所以必须先发查询；而 SDK 是<b>同步读</b> {@code PtzPreset} 配置项，
     * {@code GET /device/presetlist} 一次调用就把数据拿回来了，没有「异步等应答」这回事。
     *
     * <p>早期这里直接落到 {@link #sendControl}，对 SDK 设备抛「此控制命令暂未适配」，
     * 而前端「刷新预置位」是「先 fetchpreset 再轮询 presetlist」的固定流程，
     * 于是第一个请求就报错，用户根本走不到能出数据的第二步。
     */
    public void queryPresets(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            log.info("SDK 直连设备无需下发预置位查询（同步读，结果由 /device/presetlist 返回）: {}",
                    t.deviceId());
            return;
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.presetQuery(client().nextSn(), deviceId));
    }

    // ==================== 巡航 / 看守位 ====================

    /**
     * 巡航控制：{@code add}/{@code remove} 用 {@code 0x84}/{@code 0x85}，
     * {@code setspeed}/{@code setstay} 用 {@code 0x86}/{@code 0x87}，{@code start} 用 {@code 0x88}；
     * {@code stop} 没有专属指令码，就是普通停止（字节 4 全 0）。
     */
    public void navigate(String channelId, String command, Integer groupId, Integer preset,
                         Integer speed, Integer stay, String name) {
        String cmd = normalize(command);
        int group = groupId == null ? 1 : groupId;
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            navigateBySdk(t, cmd, group, preset, command);
            return;
        }
        switch (cmd) {
            case "query" -> sendControl(channelId,
                    deviceId -> ManscdpBuilder.cruiseTrackListQuery(client().nextSn(), deviceId));
            case "add" -> sendPtz(channelId, PtzEncoder.cruisePoint(
                    DEFAULT_ADDRESS, true, group, preset == null ? 0 : preset));
            case "remove" -> sendPtz(channelId, PtzEncoder.cruisePoint(
                    DEFAULT_ADDRESS, false, group, preset == null ? 0 : preset));
            case "setspeed" -> sendPtz(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_SPEED, group, speed == null ? 1 : speed));
            case "setstay" -> sendPtz(channelId, PtzEncoder.cruiseParam(
                    DEFAULT_ADDRESS, PtzEncoder.CRUISE_TIME, group, stay == null ? 1 : stay));
            case "start" -> sendPtz(channelId, PtzEncoder.cruiseStart(DEFAULT_ADDRESS, group));
            case "stop" -> sendPtz(channelId, PtzEncoder.stop(DEFAULT_ADDRESS));
            default -> throw BizException.badRequest("不支持的巡航指令：" + command);
        }
        log.info("巡航控制: channel={} command={} group={} preset={} speed={} stay={} name={}",
                channelId, command, groupId, preset, speed, stay, name);
    }

    /**
     * SDK 直连设备的巡航组控制。
     *
     * <p>国标的巡航组 {@code groupId} 对应大华的「巡航线号」（1~64），
     * 语义一致，可以直接映射 {@code NET_EXTPTZ_ADDTOLOOP} / {@code DELFROMLOOP} /
     * {@code CLOSELOOP}。
     *
     * <p>做不到的两项要明说：{@code setspeed} / {@code setstay} 在大华侧是
     * {@code PtzTour} 配置项的字段，不是指令参数，需要单独读改写配置；
     * {@code query} 也没有「列出所有巡航组」的指令接口。
     */
    private void navigateBySdk(Target t, String cmd, int group, Integer preset, String rawCommand) {
        var session = dahuaCapability.requireSession(t.sipTarget());
        int sdkChannel = dahuaCapability.sdkChannel(session, t.sipTarget(), t.deviceId());
        int p = preset == null ? 1 : preset;
        try {
            switch (cmd) {
                case "add" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseAddPoint(session, sdkChannel, group, p);
                case "remove" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRemovePoint(session, sdkChannel, group, p);
                case "start" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRun(session, sdkChannel, group, true);
                case "stop" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRun(session, sdkChannel, group, false);
                case "setspeed", "setstay" -> throw BizException.badRequest(
                        "大华的巡航速度/停留时间是 PtzTour 配置项字段，不是指令参数，暂不支持在线修改；"
                                + "请在设备 Web 界面设置巡航线 " + group + " 的速度与停留时间");
                case "query" -> throw BizException.badRequest(
                        "大华 SDK 没有「列出巡航组」的指令接口；"
                                + "巡航组内容在设备 Web 界面的「PTZ 巡航」页查看");
                default -> throw BizException.badRequest("不支持的巡航指令：" + rawCommand);
            }
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw dahuaCapability.translate("巡航控制", e);
        }
        log.info("大华巡航控制: device={} channel={} command={} route={} preset={}",
                t.sipTarget(), t.deviceId(), cmd, group, p);
    }

    /** 查询巡航轨迹。 */
    public void queryCruiseTrack(String channelId, Integer number) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，没有「巡航轨迹查询」接口；"
                    + "大华 SDK 的巡航组请用 /api/v1/dahua/ptz/action 直接控制");
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.cruiseTrackQuery(
                client().nextSn(), deviceId, number == null ? 0 : number));
    }

    /** 查询看守位信息。 */
    public void queryHomePosition(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，没有「看守位」概念；"
                    + "需要开机/空闲回到固定机位请用预置位 + 设备侧配置");
        }
        sendControl(channelId,
                deviceId -> ManscdpBuilder.homePositionQuery(client().nextSn(), deviceId));
    }

    /**
     * 看守位控制。它不是 PTZCmd：在 {@code <Control>} 下有自己的 {@code <HomePosition>} 元素
     * （{@code <Enabled>/<ResetTime>/<PresetIndex>}），当成 PTZCmd 发设备不理。
     */
    public void homePosition(String channelId, Boolean enabled, Integer resetTime, Integer presetIndex) {
        boolean on = Boolean.TRUE.equals(enabled);
        int reset = resetTime == null ? 0 : resetTime;
        int preset = presetIndex == null ? 1 : presetIndex;
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，没有「看守位」这个概念；"
                    + "需要空闲自动回位请先在预置位面板设好机位，再在设备 Web 界面配置空闲动作");
        }
        sendControl(channelId, deviceId -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Control><CmdType>DeviceControl</CmdType>"
                + "<SN>" + client().nextSn() + "</SN>"
                + "<DeviceID>" + deviceId + "</DeviceID>"
                + "<HomePosition><Enabled>" + (on ? 1 : 0) + "</Enabled>"
                + (on ? "<ResetTime>" + reset + "</ResetTime>"
                        + "<PresetIndex>" + preset + "</PresetIndex>" : "")
                + "</HomePosition></Control>");
    }

    // ==================== 辅助开关 ====================

    /**
     * 雨刷开关（开关编号固定 1，见标准与 wvp 实现）。
     *
     * <p>SDK 直连设备：大华把雨刷与补光灯合成一条 {@code NET_PTZ_LAMP_CONTROL} 指令，
     * 序号传 0。
     */
    public void wiper(String channelId, String command) {
        boolean on = "on".equals(normalize(command));
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaCapability.setLamp(t.sipTarget(), t.deviceId(), on, 0);
            return;
        }
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, WIPER_SWITCH_ID, on));
    }

    /** 补光灯开关（编号由设备定义，默认 2）。 */
    public void fillLight(String channelId, String command, Integer index) {
        boolean on = "on".equals(normalize(command));
        int idx = index == null ? 2 : index;
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaCapability.setLamp(t.sipTarget(), t.deviceId(), on, idx);
            return;
        }
        sendPtz(channelId, PtzEncoder.auxiliary(DEFAULT_ADDRESS, idx, on));
    }

    // ==================== 设备控制 ====================

    /**
     * 设备端录像控制：{@code start} / {@code stop}。
     *
     * <p>SDK 直连设备走<b>改录像计划</b>：大华没有「开始/停止录像」的开关接口，
     * 设备侧录像完全由 {@code Record} 配置项里的时间段决定。
     * 注意 {@code CLIENT_RecordStartEx} 录的是<b>平台机器的磁盘</b>，不是设备 SD 卡，
     * 语义不对，所以不用。
     */
    public void recordControl(String channelId, String command, Integer streamNumber) {
        boolean start;
        String value;
        switch (normalize(command)) {
            case "start" -> {
                start = true;
                value = "Record";
            }
            case "stop" -> {
                start = false;
                value = "StopRecord";
            }
            default -> throw BizException.badRequest("不支持的录像指令：" + command);
        }
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaRecordPlan.setRecording(t.sipTarget(), t.deviceId(), start);
            return;
        }
        String v = value;
        sendControl(channelId, deviceId -> ManscdpBuilder.recordControl(
                client().nextSn(), deviceId, v, streamNumber));
    }

    /**
     * 布防 / 撤防。
     *
     * <p>SDK 直连设备走 {@code Alarm} 配置项的 {@code Enable} —— 大华 IPC
     * 没有独立的布防接口，通道级报警使能就是它。
     */
    public void guard(String channelId, String command) {
        boolean on;
        String value;
        switch (normalize(command)) {
            case "setguard" -> {
                on = true;
                value = "SetGuard";
            }
            case "resetguard" -> {
                on = false;
                value = "ResetGuard";
            }
            default -> throw BizException.badRequest("不支持的布防指令：" + command);
        }
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaConfigEdit.setTopLevelBoolean(t.sipTarget(), t.deviceId(),
                    "Alarm", "Enable", on);
            return;
        }
        String v = value;
        sendControl(channelId,
                deviceId -> ManscdpBuilder.guardControl(client().nextSn(), deviceId, v));
    }

    /**
     * 设备远程重启（标准 A.2.3.1.3）。
     *
     * <p><b>SDK 直连设备重启后会断线几十秒</b>，期间预览与录像全部中断。
     */
    public void teleBoot(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaCapability.reboot(t.sipTarget());
            return;
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.teleBoot(client().nextSn(), deviceId));
    }

    /**
     * 强制关键帧（标准 A.2.3.1.7）。
     *
     * <p><b>SDK 直连设备做不到</b>：大华 NetSDK 没有强制关键帧接口，
     * 也没有对应的 {@code CtrlType}。可行路径只有在推流侧让 ZLM 重新请求 GOP，
     * 与设备无关。这里如实报错而不是假装成功。
     */
    public void forceIFrame(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，大华 SDK 没有强制关键帧接口；"
                    + "需要立即刷新画面请改用「重新打开预览」");
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.forceIFrame(client().nextSn(), deviceId));
    }

    /** 存储卡格式化（标准 A.2.3.1.13）：{@code id=0} 表示全部。 */
    public void formatSdCard(String channelId, Integer sdCardId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，当前 SDK 版本没有存储卡结构体，无法下发格式化；"
                    + "请在设备 Web 界面操作");
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.formatSdCard(
                client().nextSn(), deviceId, sdCardId == null ? 0 : sdCardId));
    }

    /** 报警复位（标准 A.2.3.1.9）。 */
    public void alarmReset(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            dahuaCapability.clearAlarm(t.sipTarget(), t.deviceId());
            return;
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.alarmReset(client().nextSn(), deviceId));
    }

    /**
     * 设备校时（标准 A.2.3.1.9 / 9.12）。设备时钟漂移会让录像 {@code StartTime/EndTime}
     * 与平台检索区间对不上，用户查「昨天下午 3 点」什么都查不到而录像其实存在。
     *
     * @param localTime 要下发的时间；{@code null} 表示平台当前时间
     */
    public void syncTime(String channelId, java.time.LocalDateTime localTime) {
        java.time.LocalDateTime t = localTime == null
                ? java.time.LocalDateTime.now() : localTime;
        Target target = target(channelId);
        if (providerOf(target.sipTarget()) != null) {
            dahuaCapability.setDeviceTime(target.sipTarget(), t);
            return;
        }
        // 格式严格 yyyy-MM-dd'T'HH:mm:ss：带毫秒或时区后缀会被设备判非法
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

    /**
     * 查询设备状态（标准 9.6）。
     *
     * <p>SDK 直连设备是同步查询的：直接打日志并在返回值之外不产生副作用。
     * 真正的状态（在线、录像中、设备时间）已经在设备巡检里持续采集，
     * 这里只回复一句「已同步查询」，避免前端以为要等异步应答。
     */
    public void queryDeviceStatus(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            var time = dahuaCapability.deviceTime(t.sipTarget());
            log.info("大华设备状态（同步查询）: device={} 设备时间={}", t.sipTarget(), time);
            return;
        }
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceStatusQuery(client().nextSn(), deviceId));
    }

    /**
     * 查询设备信息（标准 9.5）。
     *
     * <p>SDK 直连设备直接读出来打日志（序列号 / 固件 / 通道数 / 设备类型）。
     */
    public void queryDeviceInfo(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            var info = dahuaCapability.deviceInfo(t.sipTarget());
            log.info("大华设备信息: device={} sn={} 固件={} 通道数={} 设备类型={}",
                    t.sipTarget(), info.serial(), info.firmware(),
                    info.channelCount(), info.deviceType());
            return;
        }
        sendControl(channelId,
                deviceId -> ManscdpBuilder.deviceInfoQuery(client().nextSn(), deviceId));
    }

    /** 查询设备配置（标准 A.2.4.7）。 */
    public void queryConfig(String channelId, String configType) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，国标配置项查询不适用；"
                    + "大华配置请用 /api/v1/dahua/cameraconfig（图像参数）"
                    + " 或 /api/v1/dahua/record/plan（录像计划）");
        }
        sendControl(channelId, deviceId -> ManscdpBuilder.configDownloadQuery(
                client().nextSn(), deviceId, configType));
    }

    /** 查询存储卡状态（2022）。 */
    public void querySdCardStatus(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            throw BizException.badRequest("该设备通过 " + providerOf(t.sipTarget()).kind().code()
                    + " 接入，当前 SDK 版本没有存储卡状态结构体，无法查询");
        }
        sendControl(channelId,
                deviceId -> ManscdpBuilder.sdCardStatusQuery(client().nextSn(), deviceId));
    }

    /**
     * 查询 PTZ 精确状态（2022）。
     *
     * <p>SDK 直连设备是<b>同步查询</b>：直接读云台当前角度并在返回前拿到结果，
     * 所以此处只记录日志，不需要“下发+等应答”。
     */
    public void queryPtzPosition(String channelId) {
        Target t = target(channelId);
        if (providerOf(t.sipTarget()) != null) {
            int ch = dahuaCapability.sdkChannel(
                    dahuaCapability.requireSession(t.sipTarget()), t.sipTarget(), t.deviceId());
            var pos = dahuaCapability.ext().position(
                    dahuaCapability.requireSession(t.sipTarget()), ch);
            log.info("大华云台位置（同步查询）: channel={} {}", t.deviceId(),
                    pos == null ? "设备未返回"
                            : "pan=" + pos.pan() + " tilt=" + pos.tilt() + " zoom=" + pos.zoom());
            return;
        }
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

    /**
     * 设备软件升级（A.2.3.1.12）。命令发出即返回，最终结果由设备另行通知
     * （A.2.5.9 {@code DeviceUpgradeResult}）。
     *
     * <p>这里等的是 A.2.6.2 的<b>控制应答</b>（受不受理），不是升级结果 ——
     * 升级要下载固件再刷写，几分钟到几十分钟都有可能，不能同步等。
     */
    public ControlResultVo deviceUpgrade(String channelId, String firmware,
                                         String fileUrl, String manufacturer) {
        // SessionID 由平台生成：32 位十六进制，正好落在标准要求的 32~128 字节区间，
        // 设备在结果通知里原样带回，用来关联本次升级
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        ControlResultVo vo = sendControlAndAwait(channelId, sn -> ManscdpBuilder.deviceUpgrade(
                sn, channelId, firmware, fileUrl, manufacturer, sessionId));
        vo.setSessionId(sessionId);
        return vo;
    }

    /**
     * 目标跟踪控制（A.2.3.1.14）：{@code Auto} / {@code Manual} / {@code Stop}。
     *
     * <p>手动跟踪要让设备把框选位置换算成 PTZ 动作，6 个坐标缺一不可，
     * 少一个设备就算不出转向角度，所以这里直接拒掉而不是发一条残缺命令。
     *
     * <p>{@code Auto} 的跟踪算法在<b>设备侧</b>（球机智能分析固件），
     * 平台只负责把命令发对。设备不支持时标准里没有替代通路。
     */
    public ControlResultVo targetTrack(String channelId, String targetTrack, String deviceId2,
                                       Integer length, Integer width,
                                       Integer midpointX, Integer midpointY,
                                       Integer lengthX, Integer lengthY) {
        String mode = targetTrack == null ? "Stop" : targetTrack.trim();
        if (!"Auto".equalsIgnoreCase(mode) && !"Manual".equalsIgnoreCase(mode)
                && !"Stop".equalsIgnoreCase(mode)) {
            throw BizException.badRequest("TargetTrack 取值必须是 Auto / Manual / Stop：" + mode);
        }
        if ("Manual".equalsIgnoreCase(mode)
                && (length == null || width == null || midpointX == null
                || midpointY == null || lengthX == null || lengthY == null)) {
            throw BizException.badRequest(
                    "手动跟踪需要完整框选参数：length / width / midpointx / midpointy / lengthx / lengthy");
        }
        return sendControlAndAwait(channelId, sn -> ManscdpBuilder.targetTrack(
                sn, channelId, mode, deviceId2,
                length, width, midpointX, midpointY, lengthX, lengthY));
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
     * 语音喊话（标准 A.2.3.1.11 Broadcast）：平台发通知，设备回 INVITE（{@code s=AudioBroadcast}），
     * 媒体侧由 {@code BroadcastService} 建立下行通道，音频经 WebSocket 持续推送。
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

    private static String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
