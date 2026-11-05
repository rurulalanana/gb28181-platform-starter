package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.dahua.ext.DahuaPtzExt;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.dahua.internal.DahuaPtzCommand;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.service.DahuaCapabilityService;
import io.github.rurulalanana.starter.service.DahuaConfigEditService;
import io.github.rurulalanana.starter.service.DahuaRecordPlanService;
import io.github.rurulalanana.starter.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * 大华 SDK 直连设备的控制实现。
 *
 * <h3>只覆写做得到的</h3>
 * 本类<b>只声明并实现大华 SDK 真能做的能力</b>，其余全部继承
 * {@link DeviceControl} 的默认实现 —— 也就是「不支持就直接抛」。
 * 这正是统一接口带来的最大简化：改造前每多一个厂商就要在
 * 32 个方法里各加一个 {@code else} 分支，现在是「不写就是不支持」。
 *
 * <h3>被消除的一层翻译</h3>
 * 改造前国标与大华共用一条 {@code sendPtz(ptzCmd)}，大华侧要靠
 * {@code ptzCmdToSdkCommand()} 把 PTZCmd 的 hex 反解成方向词。那个函数有个致命坑：
 * 聚焦/光圈的码值 {@code 0x41}/{@code 0x42}/{@code 0x44}/{@code 0x48} 低位恰好与方向位
 * ({@code 0x01~0x08}) 重合，于是「点光圈，云台上下动；点聚焦，云台左右动」。
 *
 * <p>现在接口直接接收<b>方向词</b>，两个实现各自编码：国标编成 PTZCmd，
 * 大华直接调 SDK。翻译层连同它的坑一起消失了。
 *
 * <h3>「不支持」的原因写在这</h3>
 * {@link #unsupportedHint} 集中回答「为什么做不到」，与
 * {@link #capabilities()} 一起构成前端可用的能力表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DahuaDeviceControl implements DeviceControl {

    private final DeviceService deviceService;
    /** SDK 直连设备的预置位/巡航/灯/重启/校时等扩展能力。 */
    private final DahuaCapabilityService dahuaCapability;
    /** 录像计划（国标 Record/StopRecord 的等价实现）。 */
    private final DahuaRecordPlanService dahuaRecordPlan;
    /** 配置项开关（布撤防等）。 */
    private final DahuaConfigEditService dahuaConfigEdit;

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    /**
     * 大华真正具备的能力（其余继承默认实现的异常）。
     *
     * <p><b>必须与方法覆写保持一致</b>：这里声明了但没覆写会抛异常；
     * 覆写了但没声明会让前端把可用按钮置灰。两者都由
     * {@code DeviceControlCapabilityTest} 交叉校验。
     */
    @Override
    public EnumSet<DeviceCapability> capabilities() {
        return EnumSet.of(
                DeviceCapability.PTZ,
                DeviceCapability.FOCUS_IRIS,
                DeviceCapability.PRESET,
                DeviceCapability.PRESET_QUERY,
                DeviceCapability.CRUISE,
                DeviceCapability.AUX_SWITCH,
                DeviceCapability.RECORDING,
                DeviceCapability.GUARD,
                DeviceCapability.REBOOT,
                DeviceCapability.ALARM_RESET,
                DeviceCapability.SYNC_TIME,
                DeviceCapability.QUERY_STATUS,
                DeviceCapability.QUERY_INFO,
                DeviceCapability.QUERY_PTZ_POSITION);
    }

    @Override
    public String unsupportedHint(DeviceCapability capability) {
        return switch (capability) {
            case PTZ_PRECISE -> "大华 SDK 封装里没有绝对角度定位接口；请用预置位定位";
            case DRAG_ZOOM -> "大华 SDK 没有拉框缩放接口；请变倍后手动调整";
            case CRUISE_QUERY -> "大华 SDK 没有「列出巡航组」的指令接口；"
                    + "巡航组内容请在设备 Web 界面的「PTZ 巡航」页查看";
            case HOME_POSITION -> "大华没有「看守位」概念；需要空闲自动回位请先在预置位面板"
                    + "设好机位，再在设备 Web 界面配置空闲动作";
            case FORCE_I_FRAME -> "大华 NetSDK 没有强制关键帧接口；"
                    + "需要立即刷新画面请改用「重新打开预览」";
            case FORMAT_SD_CARD -> "当前 SDK 版本没有存储卡结构体，无法下发格式化；"
                    + "请在设备 Web 界面操作";
            case QUERY_SD_CARD -> "当前 SDK 版本没有存储卡状态结构体，无法查询";
            case DEVICE_CONFIG -> "国标 DeviceConfig 报文不适用于 SDK 直连设备；"
                    + "配置请用 /api/v1/dahua/cameraconfig（图像参数）"
                    + " 或 /api/v1/dahua/record/plan（录像计划）";
            case QUERY_CONFIG -> "国标配置项查询不适用于 SDK 直连设备；"
                    + "配置请用 /api/v1/dahua/cameraconfig 或 /api/v1/dahua/record/plan";
            case OSD_CONFIG -> "国标 OSD 配置报文不适用于 SDK 直连设备；"
                    + "OSD 请在设备 Web 界面设置";
            case SNAPSHOT_CONFIG -> "国标抓拍配置报文不适用于 SDK 直连设备";
            case DEVICE_UPGRADE -> "SDK 直连设备不支持平台下发升级包；请在设备 Web 界面升级";
            case TARGET_TRACK -> "大华 SDK 的目标跟踪由设备智能分析固件自行控制，"
                    + "平台没有下发接口；请在设备 Web 界面开启";
            case ALARM_NOTIFY -> "平台向设备下发报警通知不适用于 SDK 直连设备";
            case SUBSCRIBE -> "大华的事件订阅由平台启动时通过 SDK 自动完成，无需逐次下发";
            case TALK -> "SDK 直连设备的语音对讲走 /api/v1/dahua/talk/start"
                    + "（设备音频通道），与国标 Broadcast 不是同一套";
            default -> "";
        };
    }

    // ==================== 云台 ====================

    /**
     * 云台方向控制。
     *
     * <h3>为什么不用 {@code DeviceAccessProvider#ptz}</h3>
     * 那条入口把会话寄在 {@code DeviceContext} 上，而它由设备表里的
     * {@code remoteIp/remotePort} 构造（CLIENT 模式、无 serial）。
     * <b>设备主动注册时 {@code remoteIp} 可能是旧地址或内网地址</b>，
     * 按 host:port 查不到会话，于是报「大华设备未登录」—— 设备明明刚注册过。
     *
     * <p>所以改走 {@code DahuaCapabilityService.requireSession}：它优先按 SN
     * 取主动注册会话（与预置位、巡航、灯光等所有扩展能力同一条路径）。
     * 方向词直接交给 {@code DahuaPtzCommand} 解析，不再经过 PTZCmd 反解。
     */
    @Override
    public void ptz(Ptz cmd) {
        sendPtz(cmd.channelId(), cmd.command(), cmd.speed());
    }

    /**
     * 变倍 / 聚焦 / 光圈。
     *
     * <p>{@code DahuaPtzCommand} 里聚焦光圈的词是 {@code FOCUSNEAR}/{@code FOCUSFAR}/
     * {@code IRISIN}({@code IRISOPEN})/{@code IRISOUT}({@code IRISCLOSE})，
     * 与接口的命令词一致，直接透传即可。
     */
    @Override
    public void focusIris(FocusIris cmd) {
        sendPtz(cmd.channelId(), cmd.command(), cmd.speed());
    }

    /**
     * 下发一条云台指令。
     *
     * <p>速度口径：接口是 0~255（与国标一致），SDK 是 1~8，
     * 由 {@code DahuaPtzExt.toSdkSpeed} 折算 —— 与方向取 129 中值同一个理由：
     * 满速下轻微超调就会把机位带到画面外。
     */
    private void sendPtz(String channelId, String command, Integer speed) {
        String deviceId = deviceIdOf(channelId);
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelId);
        int sdkSpeed = DahuaPtzExt.toSdkSpeed(speedOf(speed));
        boolean stop = DahuaPtzCommand.isStop(command);
        int code;
        if (stop) {
            // 「停」不是指令码而是动作：任取一个方向码 + stop=true，SDK 会停住当前动作
            code = DahuaPtzCommand.UP.code();
        } else {
            DahuaPtzCommand parsed = DahuaPtzCommand.parse(command);
            if (parsed == null) {
                throw BizException.badRequest("不支持的云台指令：" + command);
            }
            code = parsed.code();
        }
        try {
            DahuaPtzExt.direction(session, sdkChannel, code, sdkSpeed, stop);
        } catch (IllegalStateException | IllegalArgumentException e) {
            /*
             * 转成 400：设备侧拒绝（不是球机 / 通道号错 / 会话失效）是正常的业务失败，
             * 不该按服务端故障打 ERROR + 堆栈。
             */
            throw BizException.badRequest(e.getMessage() == null
                    ? "云台指令下发失败" : e.getMessage());
        }
    }

    // ==================== 预置位 ====================

    /**
     * 预置位操作，<b>全部走设备</b>。
     *
     * <p>通道号要用 {@code sdkChannel}（IPC 是 0、NVR 是 1~N），
     * 与云台方向键同一口径 —— 用 1 去控 IPC 不报错但设备不动。
     *
     * <h3>为什么不再存平台坐标</h3>
     * 早期因为「{@code PtzPreset} 配置项读不到」误判成「该型号不支持预置点」，
     * 于是做了一套「平台存 (pan,tilt,zoom) + 绝对定位回位」的兜底。
     * 后来发现设备<b>完全支持</b>：{@code QueryDevState(0x57)} 能读到清单（含名称与坐标），
     * GOTO 精确回位（连做 3 次误差 0）。<b>设备有的就用设备的</b>。
     */
    @Override
    public void preset(Preset cmd) {
        if (cmd.preset() == null) {
            throw BizException.badRequest("缺少预置位编号");
        }
        var session = dahuaCapability.requireSession(deviceIdOf(cmd.channelId()));
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceIdOf(cmd.channelId()),
                cmd.channelId());
        var ext = dahuaCapability.ext();
        try {
            switch (normalize(cmd.command())) {
                case "set" -> ext.setPreset(session, sdkChannel, cmd.preset());
                case "goto", "call" -> ext.gotoPreset(session, sdkChannel, cmd.preset());
                case "remove", "delete" -> ext.deletePreset(session, sdkChannel, cmd.preset());
                default -> throw BizException.badRequest("不支持的预置位指令：" + cmd.command());
            }
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("预置位操作", e);
        }
    }

    /**
     * SDK 直连设备什么也不做，直接返回成功 —— 这不是偷懒：
     * 国标是「下发 PresetQuery → 等 MANSCDP 应答 → 落内存快照」，
     * 所以必须先发查询；而 SDK 是<b>同步读</b>预置位配置项，
     * {@code GET /device/presetlist} 一次调用就把数据拿回来了。
     *
     * <p>早期这里抛「此控制命令暂未适配」，而前端「刷新预置位」是
     * 「先 fetchpreset 再轮询 presetlist」的固定流程，于是第一个请求就报错，
     * 用户根本走不到能出数据的第二步。
     */
    @Override
    public void queryPresets(String channelId) {
        log.info("SDK 直连设备无需下发预置位查询（同步读，结果由 /device/presetlist 返回）: {}",
                deviceIdOf(channelId));
    }

    // ==================== 巡航 ====================

    /**
     * 巡航组控制。
     *
     * <p>国标的巡航组 {@code groupId} 对应大华的「巡航线号」（1~64），语义一致，
     * 可以直接映射 {@code NET_EXTPTZ_ADDTOLOOP} / {@code DELFROMLOOP} / {@code CLOSELOOP}。
     *
     * <p>{@code setspeed} / {@code setstay} 在大华侧是配置项字段而不是指令参数，
     * 需要单独读改写配置；{@code query} 也没有「列出所有巡航组」的指令接口。
     * 这三项明说不支持，而不是让用户点了没反应。
     */
    @Override
    public void cruise(Cruise cmd) {
        String command = normalize(cmd.command());
        int group = cmd.groupId() == null ? 1 : cmd.groupId();
        int preset = cmd.preset() == null ? 1 : cmd.preset();
        String deviceId = deviceIdOf(cmd.channelId());
        switch (command) {
            case "setspeed", "setstay" -> throw BizException.badRequest(
                    "大华的巡航速度/停留时间是 PtzTour 配置项字段，不是指令参数，暂不支持在线修改；"
                            + "请在设备 Web 界面设置巡航线 " + group + " 的速度与停留时间");
            case "query" -> throw unsupported(DeviceCapability.CRUISE_QUERY);
            default -> {
                // add / remove / start / stop 走 SDK
            }
        }
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, cmd.channelId());
        try {
            switch (command) {
                case "add" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseAddPoint(session, sdkChannel, group, preset);
                case "remove" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRemovePoint(session, sdkChannel, group, preset);
                case "start" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRun(session, sdkChannel, group, true);
                case "stop" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRun(session, sdkChannel, group, false);
                default -> throw BizException.badRequest("不支持的巡航指令：" + cmd.command());
            }
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("巡航控制", e);
        }
        log.info("大华巡航控制: device={} channel={} command={} route={} preset={}",
                deviceId, cmd.channelId(), command, group, preset);
    }

    // ==================== 辅助开关 ====================

    /**
     * 雨刷开关。大华把雨刷与补光灯合成一条 {@code NET_PTZ_LAMP_CONTROL} 指令，
     * 雨刷的序号固定传 0。
     */
    @Override
    public void wiper(String channelId, String command) {
        boolean on = "on".equals(normalize(command));
        dahuaCapability.setLamp(deviceIdOf(channelId), channelId, on, 0);
    }

    /** 补光灯开关（编号由设备定义，默认 2）。 */
    @Override
    public void fillLight(String channelId, String command, Integer index) {
        boolean on = "on".equals(normalize(command));
        dahuaCapability.setLamp(deviceIdOf(channelId), channelId, on, index == null ? 2 : index);
    }

    // ==================== 设备控制 ====================

    /**
     * 设备端录像控制，走<b>改录像计划</b>：大华没有「开始/停止录像」的开关接口，
     * 设备侧录像完全由 {@code Record} 配置项里的时间段决定。
     *
     * <p>注意 {@code CLIENT_RecordStartEx} 录的是<b>平台机器的磁盘</b>，不是设备 SD 卡，
     * 语义不对，所以不用。
     */
    @Override
    public void recording(Recording cmd) {
        boolean start = switch (normalize(cmd.command())) {
            case "start" -> true;
            case "stop" -> false;
            default -> throw BizException.badRequest("不支持的录像指令：" + cmd.command());
        };
        dahuaRecordPlan.setRecording(deviceIdOf(cmd.channelId()), cmd.channelId(), start);
    }

    /**
     * 布防 / 撤防，走 {@code Alarm} 配置项的 {@code Enable} ——
     * 大华 IPC 没有独立的布防接口，通道级报警使能就是它。
     */
    @Override
    public void guard(Arming cmd) {
        boolean on = switch (normalize(cmd.command())) {
            case "setguard" -> true;
            case "resetguard" -> false;
            default -> throw BizException.badRequest("不支持的布防指令：" + cmd.command());
        };
        dahuaConfigEdit.setTopLevelBoolean(deviceIdOf(cmd.channelId()), cmd.channelId(),
                "Alarm", "Enable", on);
    }

    /**
     * 远程重启。
     *
     * <p><b>SDK 直连设备重启后会断线几十秒</b>，期间预览与录像全部中断。
     */
    @Override
    public void reboot(String channelId) {
        dahuaCapability.reboot(deviceIdOf(channelId));
    }

    /** 报警复位：清空设备侧的报警输出与锁存状态。 */
    @Override
    public void alarmReset(String channelId) {
        dahuaCapability.clearAlarm(deviceIdOf(channelId), channelId);
    }

    /**
     * 校时。设备时钟漂移会让录像片段的时间与平台检索区间对不上 ——
     * 用户查「昨天下午 3 点」什么都查不到而录像其实存在。
     */
    @Override
    public void syncTime(SyncTime cmd) {
        dahuaCapability.setDeviceTime(deviceIdOf(cmd.channelId()),
                cmd.time() == null ? java.time.LocalDateTime.now() : cmd.time());
    }

    // ==================== 查询 ====================

    /**
     * 状态查询是<b>同步读</b>的：真正的状态（在线、录像中、设备时间）
     * 已在设备巡检里持续采集，这里只记录一条日志，避免前端以为要等异步应答。
     */
    @Override
    public void queryDeviceStatus(String channelId) {
        String deviceId = deviceIdOf(channelId);
        var time = dahuaCapability.deviceTime(deviceId);
        log.info("大华设备状态（同步查询）: device={} 设备时间={}", deviceId, time);
    }

    @Override
    public void queryDeviceInfo(String channelId) {
        String deviceId = deviceIdOf(channelId);
        var info = dahuaCapability.deviceInfo(deviceId);
        log.info("大华设备信息: device={} sn={} 固件={} 通道数={} 设备类型={}",
                deviceId, info.serial(), info.firmware(),
                info.channelCount(), info.deviceType());
    }

    @Override
    public void queryPtzPosition(String channelId) {
        String deviceId = deviceIdOf(channelId);
        var session = dahuaCapability.requireSession(deviceId);
        int ch = dahuaCapability.sdkChannel(session, deviceId, channelId);
        var pos = dahuaCapability.ext().position(session, ch);
        log.info("大华云台位置（同步查询）: channel={} {}", channelId,
                pos == null ? "设备未返回"
                        : "pan=" + pos.pan() + " tilt=" + pos.tilt() + " zoom=" + pos.zoom());
    }

    // ==================== 内部 ====================

    /**
     * 通道编码 → 设备编码。
     *
     * <p>国标里控制命令的 {@code <DeviceID>} 是通道编码；大华 SDK 的绝大多数接口
     * 需要<b>设备编码</b>（SN 或 device_id），通道只用数字下标。
     * 查不到所属设备时退回通道编码本身 —— 与旧行为一致（IPC 场景二者相同）。
     */
    private String deviceIdOf(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw BizException.badRequest("缺少通道编码");
        }
        String owner = deviceService.deviceIdOfChannel(channelId);
        return owner == null ? channelId : owner;
    }

    /**
     * 云台速度：接口口径 0~255，{@code toSdkSpeed} 内部再折算成 SDK 的 1~8。
     * 取中值 129 而非满速：SDK 方向控制是连续动作，满速下轻微超调就会把机位带到画面外。
     */
    private static int speedOf(Integer speed) {
        return speed == null ? 129 : Math.max(0, Math.min(255, speed));
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toLowerCase();
    }
}
