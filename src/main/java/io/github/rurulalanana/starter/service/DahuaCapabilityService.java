package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaCgiAccess;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.config.DahuaProperties;
import io.github.rurulalanana.starter.entity.GbDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 大华 SDK 扩展能力的平台侧中枢。
 *
 * <h3>为什么需要这一层</h3>
 * {@code sdk-dahua} 里的 {@link DahuaExtAccess} 提供的是「大华语义」的裸能力
 * （预置位号段、JSON 配置项名、事件码）。平台要的是「按 deviceId 干活」：
 * 查设备记录 → 拿地址与凭据 → 找已登录会话 → 调 SDK → 把异常翻成中文业务错误。
 * 这三段逻辑如果散落在每个 Service 里，会出现「有的地方忘了判 source」
 * 「有的地方没把错误码转成提示」这类不一致。
 *
 * <h3>为什么用 ObjectProvider 延迟取</h3>
 * {@code DahuaExtAccess} 本身不加载原生库，但它调用的 {@code DahuaSdk.get()}
 * 会。用 {@link ObjectProvider} 保证「没装 SDK 时平台照常启动，只有大华接口报错」，
 * 与 {@code DeviceAccessRegistry} 的口径一致。
 *
 * <h3>会话从哪来</h3>
 * 复用 {@link DahuaSdk#session}：它会按 SN 或 host:port 复用已登录会话，
 * 句柄失效时自动重登。**不要在业务代码里自己 CLIENT_Login** ——
 * 那会绕开注册表，设备换 IP 后拿到的是死地址。
 */
@Slf4j
@Service
public class DahuaCapabilityService {

    private final DeviceService deviceService;
    private final ObjectProvider<DahuaExtAccess> extProvider;
    /** 设备 Web CGI 通道（配置写入兜底），无状态可复用。 */
    private final DahuaCgiAccess cgi = new DahuaCgiAccess();
    private final io.github.rurulalanana.dahua.ext.DahuaRpcAccess rpc =
            new io.github.rurulalanana.dahua.ext.DahuaRpcAccess();
    private final DahuaProperties dahuaProperties;

    public DahuaCapabilityService(DeviceService deviceService,
                                  ObjectProvider<DahuaExtAccess> extProvider,
                                  DahuaProperties dahuaProperties) {
        this.deviceService = deviceService;
        this.extProvider = extProvider;
        this.dahuaProperties = dahuaProperties;
    }

    // ==================== 通用：取会话 ====================

    /**
     * 取一台大华设备已登录的会话。
     *
     * @param deviceId 设备编号（{@code gb_device.device_id}）
     * @return 会话；设备不是大华、或登录失败时为空
     */
    public Optional<DahuaSdk.Session> session(String deviceId) {
        GbDevice d = findDahuaDevice(deviceId);
        if (d == null) {
            return Optional.empty();
        }
        String host = d.getRemoteIp();
        int port = d.getRemotePort() == null ? 0 : d.getRemotePort();
        if (host == null || host.isBlank() || port <= 0) {
            return Optional.empty();
        }
        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            log.debug("大华 SDK 不可用: {}", sdk.loadError());
            return Optional.empty();
        }
        /*
         * 用 CLIENT 模式登录，与云台链路完全一致。
         *
         * 主动注册设备虽然当初是设备连上来的，但平台侧已经把它当时的 IP
         * 与 SDK 端口写进了 gb_device（见 DahuaAutoProvisionRunner）。
         * 有明确地址时 CLIENT 模式最直接；换成 SERVER 模式反而要依赖
         * 内存注册表，进程重启后那份表是空的，接口会全挂。
         */
        DahuaSdk.Session s = sdk.session(host, port, d.getUsername(), d.getPassword(),
                ConnectMode.CLIENT, null);
        return Optional.ofNullable(s);
    }

    /** 查设备记录，并确认它确实是大华接入。 */
    private GbDevice findDahuaDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        GbDevice d = deviceService.findDevice(deviceId);
        if (d == null) {
            // 传进来的可能是通道编码，反查所属设备
            String owner = deviceService.deviceIdOfChannel(deviceId);
            d = owner == null ? null : deviceService.findDevice(owner);
        }
        if (d == null) {
            return null;
        }
        return DeviceKind.of(d.getSource()) == DeviceKind.DAHUA ? d : null;
    }

    /**
     * 取会话，取不到就抛业务异常。
     *
     * <p>错误信息必须能指导操作：只说「设备不可用」等于没说。
     */
    public DahuaSdk.Session requireSession(String deviceId) {
        GbDevice d = findDahuaDevice(deviceId);
        if (d == null) {
            GbDevice any = deviceService.findDevice(deviceId);
            if (any == null) {
                String owner = deviceService.deviceIdOfChannel(deviceId);
                any = owner == null ? null : deviceService.findDevice(owner);
            }
            if (any == null) {
                throw BizException.notFound("设备不存在：" + deviceId);
            }
            throw BizException.badRequest("该接口仅适用于大华 SDK 直连设备：" + any.getDeviceId());
        }
        return session(d.getDeviceId()).orElseThrow(() -> BizException.badRequest(
                "大华设备未登录（检查设备是否在线、账号密码是否正确）：" + d.getDeviceId()));
    }

    /** 该设备是否走大华 SDK 接入。 */
    public boolean isDahua(String deviceId) {
        return findDahuaDevice(deviceId) != null;
    }

    /**
     * 取扩展能力对象。
     *
     * <p>每次调用都重新取：{@code DahuaExtAccess} 本身无状态（回调强引用放在实例字段里），
     * 但 Spring 单例下不会重复创建，代价可以忽略。
     */
    public DahuaExtAccess ext() {
        DahuaExtAccess ext = extProvider.getIfAvailable();
        if (ext == null) {
            throw BizException.badRequest(
                    "大华 SDK 模块未安装或未启用（sdk-dahua）；见 docs/大华SDK接入.md");
        }
        return ext;
    }

    /**
     * 取设备 Web CGI 通道（配置写入的兜底路径）。
     *
     * <p>只在 SDK 写入被设备拒绝时用：部分型号的场景数组类配置项
     * （{@code VideoColor} / {@code VideoInSharpness} …）通过
     * {@code CLIENT_SetNewDevConfig} 一律 {@code err=12}，
     * 而设备自己的 Web 界面能改，走的就是这条 {@code configManager.cgi}。
     * 详见 {@code DahuaCgiAccess} 的类注释。
     */
    public DahuaCgiAccess cgi() {
        return cgi;
    }

    /**
     * RPC2 通道（懒创建，全局共用一个：session 与 object id 需要跨调用缓存）。
     *
     * <p>只在「CGI 与 netsdk 都没有对应能力」时用，见 {@code DahuaRpcAccess} 类注释。
     */
    public io.github.rurulalanana.dahua.ext.DahuaRpcAccess rpc() {
        return rpc;
    }

    /** 设备 Web（HTTP CGI）端口，见 {@code dahua.http-port}。 */
    public int httpPort() {
        return dahuaProperties == null ? DahuaCgiAccess.DEFAULT_HTTP_PORT
                : dahuaProperties.getHttpPort();
    }

    /**
     * 把 SDK 异常翻成业务异常。
     *
     * <p>统一在这里转换的理由：SDK 异常带 {@code sdkError} 与 {@code hint}，
     * 直接抛出去前端只看到英文错误码；而每处调用点各写一遍转换必然漏。
     *
     * <h3>为什么不加「{@code action} 失败：」前缀</h3>
     * {@code sdk-dahua} 抛出的消息本身已经带主语（「对讲建立失败」「抓图失败（通道 0）」
     * 「转到预置位 1 失败」…），再拼前缀会变成
     * 「建立对讲失败：对讲建立失败（…）」这种读起来像结巴的文案。
     * {@code action} 只用于日志标签，方便按动作检索。
     *
     * <p>同理<b>不再追加 {@code hint()}</b> ——
     * {@link DahuaSdkException#getMessage()} 已经把 hint 拼进去了。
     *
     * @param action 动作描述（只进日志，不拼进用户可见消息）
     */
    public BizException translate(String action, DahuaSdkException e) {
        log.warn("大华{}失败: {} err=0x{}", action, e.getMessage(),
                Integer.toHexString(e.sdkError()));
        return BizException.badRequest(e.getMessage());
    }

    // ==================== 通道号换算（IPC 用 0，NVR 用 1~N）====================

    /**
     * 把业务通道编码换算成 SDK 通道号。
     *
     * <p><b>两套编号并存，不能混用</b>：
     * <ul>
     *   <li>取流（RTSP）用 {@code channel=<N>}，N 从 1 起；</li>
     *   <li>云台/抓图/配置的 SDK 通道号：IPC 是 <b>0</b>（设备自身），NVR 是 1~N。</li>
     * </ul>
     * 拿 1 去控 IPC 不会报错，SDK 照样返回 true，只是设备什么都不做 ——
     * 实测同一台 IPC 通道 0 能转、通道 1 死命令。
     *
     * @param session     已登录会话（判定 IPC/NVR）
     * @param channelCode 业务通道编码，如 {@code 10001-1}
     * @return SDK 通道号
     */
    public int sdkChannel(DahuaSdk.Session session, String deviceId, String channelCode) {
        int index = io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channelCode);
        return session.ipc() ? Math.max(0, index - 1) : index;
    }

    /** 取流用的通道号（RTSP 的 {@code channel=} 参数，恒从 1 起）。 */
    public int streamChannel(String deviceId, String channelCode) {
        return io.github.rurulalanana.dahua.DahuaDeviceAccessProvider
                .channelIndexOf(deviceId, channelCode);
    }

    // ==================== 局域网发现 ====================

    /**
     * 局域网发现设备。
     *
     * @param localIp   本机网卡地址；{@code null} 由 SDK 自选
     * @param timeoutMs 收集时长
     * @return 发现列表（可能为空）
     */
    public List<DahuaExtAccess.Discovered> discoverLan(String localIp, long timeoutMs) {
        try {
            return ext().discoverLan(localIp, timeoutMs);
        } catch (DahuaSdkException e) {
            throw translate("局域网设备发现", e);
        }
    }

    /**
     * 登出并丢弃一台设备的 SDK 会话。
     *
     * <p><b>为什么删除设备前必须调它</b>：SDK 会话是平台主动握着的一条长连接，
     * 删掉 {@code gb_device} 记录不会自动断开它 —— 设备侧仍显示「有客户端登录」，
     * 而且那段内存（会话对象 + 原生句柄）会一直留着。
     *
     * <p>失败只记日志不抛：设备可能已经离线/重启，登不掉也不该阻止删除档案。
     *
     * @param deviceId 设备编号
     */
    public void logoutQuietly(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            GbDevice d = findDahuaDevice(deviceId);
            if (d == null) {
                return;
            }
            DahuaSdk sdk = DahuaSdk.get();
            if (!sdk.init()) {
                return;
            }
            // SN 索引与 host:port 索引都要清，两个都可能命中
            sdk.logoutBySerial(deviceId);
            if (d.getRemoteIp() != null && d.getRemotePort() != null && d.getRemotePort() > 0) {
                sdk.logout(d.getRemoteIp(), d.getRemotePort());
            }
            log.info("已登出大华 SDK 会话: device={} {}:{}", deviceId, d.getRemoteIp(), d.getRemotePort());
        } catch (Exception e) {
            log.warn("登出大华 SDK 会话失败（不影响删除档案）: device={} {}", deviceId, e.getMessage());
        }
    }

    // ==================== 云台扩展（巡航 / 巡迹 / 限位 / 重启）====================

    /**
     * 执行一次云台扩展操作。
     *
     * <p>用「动作名 + 参数」而不是为每个功能开一个方法：云台扩展有十几种，
     * 逐个包方法会得到一堆签名相似、极易传错参数的函数；而它们的共同点
     * 正是「都是一条 DHPTZControl 指令」。控制器只负责把 HTTP 参数翻译成动作名。
     *
     * @param action 见 {@code DahuaPtzActions}
     * @return 是否需要重启设备
     */
    public boolean ptzAction(String deviceId, String channelCode, String action,
                             Integer route, Integer preset, Integer mode, Boolean start) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        int r = route == null ? 1 : route;
        int p = preset == null ? 1 : preset;
        int m = mode == null ? 1 : mode;
        boolean on = start == null || start;
        try {
            switch (action == null ? "" : action.trim().toLowerCase()) {
                case "cruise-add" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseAddPoint(session, sdkChannel, r, p);
                case "cruise-remove" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .cruiseRemovePoint(session, sdkChannel, r, p);
                /*
                 * 巡航启停：走 RPC2，与设备「巡航群組」页的「執行」开关同一条通路
                 * （ptz.startTour / ptz.stopTour），副作用与设备界面完全一致。
                 *
                 * <p><b>为什么带 SDK 兜底</b>：设备在<b>巡航进行中</b>会锁住部分设置，
                 * 且 RPC2 在高负载时会短时拒绝。只走 RPC2 的话可能出现「能开不能停」——
                 * 用户再也停不下来。所以失败时改用 SDK 的「清除巡航线路」。
                 *
                 * <p>RPC2 登录细节见 {@code DahuaRpcAccess.login()}：走 /RPC2_Login、
                 * 两步挑战、摘要用 MD5(user:realm:pass) 转大写再套一层。
                 */
                case "cruise-run" -> {
                    try {
                        if (on) {
                            rpc().startTour(session, httpPort(), r);
                        } else {
                            rpc().stopTour(session, httpPort(), r);
                        }
                    } catch (DahuaSdkException rpcErr) {
                        log.warn("RPC2 {}巡航失败，改用 SDK 兜底: device={} route={} {}",
                                on ? "启动" : "停止", deviceId, r, rpcErr.getMessage());
                        io.github.rurulalanana.dahua.ext.DahuaPtzExt
                                .cruiseRun(session, sdkChannel, r, on);
                    }
                }
                /*
                 * 巡迹：设备界面也是走 RPC2，保持一致；失败再退 SDK。
                 */
                case "pattern-record" -> rpc().startPatternRecord(session, httpPort(), m, null);
                case "pattern-record-stop" -> rpc().stopPatternRecord(session, httpPort(), m);
                case "pattern-run" -> {
                    if (on) {
                        rpc().startPatternReplay(session, httpPort(), m);
                    } else {
                        rpc().stopPatternReplay(session, httpPort(), m);
                    }
                }
                case "pattern-delete" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .patternDelete(session, sdkChannel, m);
                case "limit-left" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .setLeftLimit(session, sdkChannel, r);
                case "limit-right" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .setRightLimit(session, sdkChannel, r);
                case "limit-up" -> rpc().markLimit(session, httpPort(),
                        io.github.rurulalanana.dahua.ext.DahuaRpcAccess.LIMIT_TYPE_UPPER);
                case "limit-down" -> rpc().markLimit(session, httpPort(),
                        io.github.rurulalanana.dahua.ext.DahuaRpcAccess.LIMIT_TYPE_LOWER);
                case "scan-limit-left" -> rpc().setScanLimit(session, httpPort(), r, true);
                case "scan-limit-right" -> rpc().setScanLimit(session, httpPort(), r, false);
                case "line-scan" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .lineScan(session, sdkChannel, r, on);
                case "pan-cruise" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt
                        .panCruise(session, sdkChannel, on);
                case "reboot" -> io.github.rurulalanana.dahua.ext.DahuaPtzExt.reboot(session);
                /*
                 * PTZ 恢复出厂默认（设备「PTZ 維護」页的「預設值」）。
                 * 会清空预置位/巡航组/线扫/巡迹，属破坏性操作，前端必须二次确认。
                 */
                case "ptz-default" -> rpc().resetPtzDefaults(session, httpPort());
                default -> throw BizException.badRequest("不支持的云台动作：" + action);
            }
        } catch (DahuaSdkException e) {
            throw translate("云台动作 " + action, e);
        }
        log.info("大华云台动作已执行: device={} channel={} action={} route={} preset={} mode={} start={}",
                deviceId, channelCode, action, r, p, m, on);
        return true;
    }

    /**
     * 写云台速度（手动 / 巡迹）。
     *
     * <p>写完立即回读比对：设备对 {@code PtzSpeed} 有取值校验（只认 0~4），
     * 越界时某些入口会返回成功但不写入，所以不能只信「返回 OK」。
     */
    public void setPtzSpeed(String deviceId, int channel, int ptzSpeed, int patternSpeed) {
        var session = requireSession(deviceId);
        try {
            java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
            kv.put("PtzSpeed[" + channel + "].PtzSpeed", String.valueOf(ptzSpeed));
            kv.put("PtzSpeed[" + channel + "].PatternSpeed", String.valueOf(patternSpeed));
            cgi.write(session, kv, httpPort());

            // 回读确认：设备返回 OK 不等于写进去了
            java.util.Map<String, String> back = cgi.read(session, "PtzSpeed", httpPort());
            int gotPtz = parseInt(back.get("PtzSpeed[" + channel + "].PtzSpeed"), -1);
            int gotPat = parseInt(back.get("PtzSpeed[" + channel + "].PatternSpeed"), -1);
            if (gotPtz != ptzSpeed || gotPat != patternSpeed) {
                throw BizException.badRequest("云台速度写入未生效（回读 手动=" + gotPtz
                        + " 巡迹=" + gotPat + "）。该型号可能不支持这些取值");
            }
        } catch (DahuaSdkException e) {
            throw translate("写入云台速度", e);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ==================== 设备级控制（重启 / 校时 / 报警 / 灯光雨刷 / 信息）====================

    /**
     * 重启设备整机。
     *
     * <p><b>设备会断线几十秒到两分钟</b>，期间预览与录像全部中断。
     * 调用方应先确认无人在看画面。
     */
    public void reboot(String deviceId) {
        var session = requireSession(deviceId);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.reboot(session);
        } catch (DahuaSdkException e) {
            throw translate("设备重启", e);
        }
        log.info("大华设备重启指令已下发: device={}", deviceId);
    }

    /** 读设备当前时间；读不到返回 {@code null}。 */
    public java.time.LocalDateTime deviceTime(String deviceId) {
        return io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                .queryTime(requireSession(deviceId));
    }

    /**
     * 设备校时。
     *
     * @param time 目标时间，{@code null} 表示平台当前时间
     */
    public void setDeviceTime(String deviceId, java.time.LocalDateTime time) {
        var session = requireSession(deviceId);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.setTime(session, time);
        } catch (DahuaSdkException e) {
            throw translate("设备校时", e);
        }
        log.info("大华设备已校时: device={} time={}", deviceId, time);
    }

    /** 报警复位（清掉通道当前的报警状态）。 */
    public void clearAlarm(String deviceId, String channelCode) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                    .clearAlarm(session, sdkChannel);
        } catch (DahuaSdkException e) {
            throw translate("报警复位", e);
        }
    }

    /**
     * 灯光 / 雨刷。
     *
     * <p>大华把补光灯与雨刷合成一条指令，靠 {@code index} 区分。
     * <b>返回值不能证明设备动作了</b>，只能看画面确认。
     */
    public void setLamp(String deviceId, String channelCode, boolean on, int index) {
        var session = requireSession(deviceId);
        int sdkChannel = sdkChannel(session, deviceId, channelCode);
        try {
            io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                    .setLamp(session, sdkChannel, on, index);
        } catch (DahuaSdkException e) {
            throw translate("灯光雨刷控制", e);
        }
    }

    /** 设备基本信息。 */
    public io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl.DeviceInfo deviceInfo(String deviceId) {
        return io.github.rurulalanana.dahua.ext.DahuaDeviceCtrl
                .deviceInfo(requireSession(deviceId));
    }
}
