package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.service.DahuaCameraConfigService;
import io.github.rurulalanana.starter.service.DahuaCapabilityService;
import io.github.rurulalanana.starter.service.DahuaDownloadService;
import io.github.rurulalanana.dahua.DahuaRegisterTable;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import io.github.rurulalanana.starter.config.DahuaProperties;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.service.DeviceProvisionService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.DahuaPtzConfigService;
import io.github.rurulalanana.starter.service.DahuaRecordPlanService;
import io.github.rurulalanana.starter.service.DahuaTalkService;
import io.github.rurulalanana.starter.vo.DahuaPtzActionResultVo;
import io.github.rurulalanana.starter.vo.TalkSessionVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 大华 SDK 扩展能力接口。
 *
 * <p>与 {@code /api/v1/control/*} 的分工：
 * <ul>
 *   <li>{@code control/*} 是<b>协议无关</b>的控制入口，国标设备与 SDK 设备都能调；</li>
 *   <li>本控制器是<b>大华专属</b>的长尾能力（巡航组、巡迹、限位、相机参数、对讲），
 *       国标协议里没有对应概念，所以不能放在通用入口下。</li>
 * </ul>
 *
 * <p>所有接口对国标设备都会明确报「仅适用于大华 SDK 直连设备」，
 * 而不是静默失败或抛空指针。
 */
@Slf4j
@RestController
@Tag(name = "大华扩展能力", description = "巡航/巡迹/限位/相机参数/对讲/录像下载/局域网发现")
@RequestMapping("/api/v1/dahua")
@RequiredArgsConstructor
public class DahuaController {

    private final DahuaCapabilityService capability;
    private final DahuaCameraConfigService cameraConfig;
    private final DahuaTalkService talkService;
    private final DahuaDownloadService downloadService;
    private final DahuaRecordPlanService recordPlanService;
    private final DahuaPtzConfigService ptzConfigService;
    private final DahuaRegisterTable registerTable;
    private final DeviceProvisionService provisionService;
    private final DeviceService deviceService;
    private final DahuaProperties dahuaProperties;

    // ==================== 云台扩展 ====================

    /**
     * 云台扩展动作。
     *
     * <p>{@code action} 取值：
     * <table>
     *   <tr><td>cruise-add</td><td>把 preset 号预置位加入 route 号巡航线</td></tr>
     *   <tr><td>cruise-remove</td><td>从巡航线移除预置位</td></tr>
     *   <tr><td>cruise-run</td><td>开始/停止巡航线（start=true/false）</td></tr>
     *   <tr><td>pattern-record / pattern-record-stop</td><td>巡迹录制开始/结束（mode 1~8）</td></tr>
     *   <tr><td>pattern-run</td><td>运行/停止巡迹</td></tr>
     *   <tr><td>pattern-delete</td><td>删除巡迹</td></tr>
     *   <tr><td>limit-left / limit-right</td><td>把当前位置设为左/右限位</td></tr>
     *   <tr><td>line-scan</td><td>开始/停止线扫（需先设限位）</td></tr>
     *   <tr><td>pan-cruise</td><td>开始/停止水平 360° 巡航</td></tr>
     *   <tr><td>reboot</td><td>重启云台</td></tr>
     * </table>
     */
    @Operation(summary = "云台扩展动作（巡航组/巡迹/限位/线扫/重启）")
    @PostMapping("/ptz/action")
    public ApiResponse<DahuaPtzActionResultVo> ptzAction(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam String action,
            @RequestParam(required = false) Integer route,
            @RequestParam(required = false) Integer preset,
            @RequestParam(required = false) Integer mode,
            @RequestParam(required = false) Boolean start) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        capability.ptzAction(serial, channel, action, route, preset, mode, start);
        DahuaPtzActionResultVo vo = new DahuaPtzActionResultVo();
        vo.setAction(action);
        vo.setChannelId(channel);
        vo.setOk(true);
        vo.setNote(noteOf(action));
        return ApiResponse.ok(vo);
    }

    /** 各动作需要提醒用户的前置条件。写清楚比让用户自己猜好。 */
    private static String noteOf(String action) {
        String a = action == null ? "" : action.toLowerCase();
        return switch (a) {
            case "line-scan" -> "线扫需要先设置左右边界，否则设备可能拒绝或全范围乱扫";
            case "cruise-run" -> "巡航线里必须先加入预置位，空线不会动";
            case "limit-left", "limit-right" ->
                    "线扫左右边界以当前镜头位置为准，请先把球机转到目标位置；"
                            + "这与设备界面「PTZ旋轉限制」的上下限位不是同一组参数";
            case "limit-up", "limit-down" ->
                    "上下限位以当前镜头位置为准，请先把球机转到目标位置；"
                            + "设置后需在「旋轉限制」里启用才生效";
            case "reboot" -> "云台重启会短暂离线（镜头回中），属正常现象";
            default -> "指令已下发；云台动作是异步的，以画面为准";
        };
    }

    // ==================== 主动注册（设备自己连上来，但没有账号密码）====================

    /**
     * 注册表里的<b>待办</b>设备清单（只含尚未纳管的）。
     *
     * <p><b>为什么要这个接口</b>：主动注册只解决「设备能连上来」，不解决「平台知道它的密码」。
     * 不同现场的设备密码各不相同，不能靠一个全局默认值通吃 —— 所以注册上来的设备先在这里排队，
     * 由用户逐台补账号密码再纳管。
     *
     * <p><b>已纳管的直接不返回</b>：这个弹窗的语义是「还有哪些设备等着补凭据」，
     * 已经进了设备管理列表的再混在里面只会让人以为「纳管没生效」。
     * 早期代码算出了 {@code managed} 却照样塞进列表（注释写的是「不再显示为待办」，
     * 实现却是全量返回），前端只好给它挂一个「已纳管」标签和一个没意义的「移除」按钮。
     */
    @Operation(summary = "主动注册设备清单（仅待补账号密码的）")
    @GetMapping("/register/list")
    public ApiResponse<List<RegisterEntryVo>> registerList() {
        StpUtil.checkLogin();
        int sdkPort = dahuaProperties.getServerMode().getSdkPort();
        List<RegisterEntryVo> list = new java.util.ArrayList<>();
        for (DeviceRegisterTable.RegisteredDevice d : registerTable.list()) {
            GbDevice existing = deviceService.findDevice(d.serial());
            // 已在设备管理列表里 → 不属待办，跳过
            if (existing != null) {
                continue;
            }
            RegisterEntryVo vo = new RegisterEntryVo();
            vo.setSerial(d.serial());
            vo.setIp(d.ip());
            // 展示设备服务端口（sdk-port）。回调里的源端口只在本次注册有效，不能给用户看
            vo.setSdkPort(sdkPort);
            vo.setLastSeenAt(java.time.Instant.ofEpochMilli(d.lastSeenAt())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            vo.setManaged(false);
            vo.setOnline(false);
            list.add(vo);
        }
        return ApiResponse.ok(list);
    }

    /**
     * 用用户输入的账号密码纳管一台已注册的设备。
     *
     * <p>纳管后立刻拉一次目录：<b>只有登录成功才会写 online</b>，密码填错时用户能马上看到
     * 「连接失败」而不是「保存成功但设备一直离线」。
     */
    @Operation(summary = "纳管已注册设备（需填设备账号密码）")
    @PostMapping("/register/adopt")
    public ApiResponse<Void> adoptRegistered(@RequestParam String serial,
                                             @RequestParam String username,
                                             @RequestParam String password,
                                             @RequestParam(required = false) Integer port,
                                             @RequestParam(required = false) String name) {
        StpUtil.checkLogin();
        DeviceRegisterTable.RegisteredDevice entry = registerTable.lookup(serial);
        if (entry == null) {
            throw BizException.badRequest("该设备已不在注册表中（可能离线超时），请改用「按 IP 添加」：" + serial);
        }
        int sdkPort = port == null || port <= 0
                ? dahuaProperties.getServerMode().getSdkPort() : port;
        provisionService.addDahua(serial, entry.ip(), sdkPort, username, password, name);
        try {
            provisionService.refreshCatalog(serial);
        } catch (Exception e) {
            // 账号密码对但目录拉取失败不该回滚档案：设备先入表，用户可再点「同步目录」
            log.warn("纳管后同步目录失败(device={}): {}", serial, e.getMessage());
            throw BizException.badRequest("设备已入库，但登录/同步目录失败：" + e.getMessage()
                    + "。请检查账号密码是否属于该设备");
        }
        log.info("已纳管主动注册设备: {} @ {}:{}", serial, entry.ip(), sdkPort);
        return ApiResponse.ok(null);
    }

    /** 从注册表移除一台设备（不纳管，只是别再列出来）。设备再次注册时还会出现。 */
    @Operation(summary = "忽略一台已注册设备")
    @PostMapping("/register/ignore")
    public ApiResponse<Void> ignoreRegistered(@RequestParam String serial) {
        StpUtil.checkLogin();
        registerTable.unregister(serial);
        return ApiResponse.ok(null);
    }

    /** 注册表条目（给前端排队用）。 */
    @lombok.Data
    public static class RegisterEntryVo {
        private String serial;
        private String ip;
        /** 平台配置的 SDK 端口（不是注册回调里的源端口）。 */
        private Integer sdkPort;
        private java.time.LocalDateTime lastSeenAt;
        /** 是否已在设备表中（已纳管的不再需要填密码）。 */
        private boolean managed;
        private boolean online;
    }

    // ==================== 云台配置（巡航组 / 速度 / 空闲与定时动作）====================

    /**
     * 读云台配置快照。
     *
     * <p>一次性返回巡航组、速度、自动动作、限位状态 —— UI 一个页面要用到全部四项，
     * 分四个接口会让「打开面板」变成四次并发请求 + 四套 loading 状态。
     */
    @Operation(summary = "云台配置快照（巡航组/速度/空闲与定时动作/限位）")
    @GetMapping("/ptz/config")
    public ApiResponse<DahuaPtzConfigService.PtzConfig> ptzConfig(
            @RequestParam String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        return ApiResponse.ok(ptzConfigService.read(serial, code));
    }

    /**
     * 只读「当前运行状态」——给前端轮询用（{@code PtzMovement} 一项）。
     *
     * <p>为什么单独开一个：运行状态易变（启停只在下发那一刻变），
     * 而 {@code /ptz/config} 要读 9 个配置项、上百 KB。为了让几个「執行」开关
     * 保持准确，每 3 秒拉一次整份配置既慢又压设备；这个接口只读一项。
     */
    @Operation(summary = "当前运行的云台动作（轮询用）")
    @GetMapping("/ptz/running")
    public ApiResponse<DahuaPtzConfigService.RunningCfg> ptzRunning(
            @RequestParam String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        return ApiResponse.ok(ptzConfigService.readRunning(serial, code));
    }

    /**
     * 开机动作（平台侧）已移除。
     *
     * <p>原先是平台自己发明的：设备「PTZ設置」的 12 个菜单里<b>没有「開機動作」</b>
     * （預置點/巡航群組/線性掃描/巡跡/水準旋轉/PTZ速度/閒置動作/充電/
     * PTZ旋轉限制/已排定工作/PTZ 維護/協議），设备根本不支持上电动作。
     * 平台硬做一份只会造成「界面上有、设备上找不到对应项」的困惑，
     * 而且它还有一个每 20 秒巡检并自动动云台的 {@code @Scheduled}，
     * 隐藏着比没有更危险。相关 Service 一并删除。
     */

    /** 保存一条巡航线（整条覆盖：32 个固定槽位，空槽自动清空）。 */
    @Operation(summary = "保存巡航组（整条覆盖）")
    @PostMapping("/ptz/tour")
    public ApiResponse<Void> saveTour(@RequestParam String serial,
                                      @RequestParam(required = false) String code,
                                      @RequestBody DahuaPtzConfigService.Tour tour) {
        StpUtil.checkLogin();
        ptzConfigService.saveTour(serial, code, tour);
        return ApiResponse.ok(null);
    }

    /** 保存云台速度（手动速度 / 巡迹速度）。 */
    @Operation(summary = "保存云台速度")
    @PostMapping("/ptz/speed")
    public ApiResponse<Void> savePtzSpeed(@RequestParam String serial,
                                          @RequestParam(required = false) String code,
                                          @RequestBody DahuaPtzConfigService.SpeedCfg speed) {
        StpUtil.checkLogin();
        ptzConfigService.saveSpeed(serial, code, speed);
        return ApiResponse.ok(null);
    }

    /**
     * 保存水平旋转速度（设备「水準旋轉」页的「旋轉速度」滑块）。
     *
     * <p>写的是 {@code AutoPan[ch].PanSpeed}（1~8），与 {@code /ptz/speed} 的
     * {@code PtzSpeed}（0~4）是两个独立字段，不能混用。
     */
    @Operation(summary = "保存水平旋转速度")
    @PostMapping("/ptz/autopan")
    public ApiResponse<Void> saveAutoPan(@RequestParam String serial,
                                         @RequestParam(required = false) String code,
                                         @RequestBody DahuaPtzConfigService.AutoPanCfg cfg) {
        StpUtil.checkLogin();
        ptzConfigService.saveAutoPan(serial, code, cfg);
        return ApiResponse.ok(null);
    }

    /** 保存线扫参数（速度）。左右边界是即时动作，走 {@code /ptz/action} 的 scan-limit-* 。 */
    @Operation(summary = "保存线扫参数（速度）")
    @PostMapping("/ptz/scan")
    public ApiResponse<Void> saveScan(@RequestParam String serial,
                                      @RequestParam(required = false) String code,
                                      @RequestBody ScanRequest req) {
        StpUtil.checkLogin();
        ptzConfigService.saveScan(serial, code, req.scanId(), req.speed());
        return ApiResponse.ok(null);
    }

    /** 线扫入参。{@code scanId} 是 1-based 的业务编号。 */
    public record ScanRequest(int scanId, int speed) {
    }

    /**
     * 保存巡迹（名称 + 停留时间）。
     *
     * <p>两者在设备上是两个配置项：名称在 {@code AutoPattern}，停留时间在
     * {@code PatternPlayDetail}（毫秒）。{@code staySeconds} 传 null 表示不改。
     */
    @Operation(summary = "保存巡迹（名称 / 停留时间）")
    @PostMapping("/ptz/pattern")
    public ApiResponse<Void> savePattern(@RequestParam String serial,
                                         @RequestParam(required = false) String code,
                                         @RequestBody PatternRequest req) {
        StpUtil.checkLogin();
        ptzConfigService.savePattern(serial, code, req.patternId(), req.name(), req.staySeconds());
        return ApiResponse.ok(null);
    }

    /**
     * 巡迹入参。{@code patternId} 是 1-based 的业务编号。
     *
     * @param staySeconds 停留秒数，{@code null} = 不改
     */
    public record PatternRequest(int patternId, String name, Integer staySeconds) {
    }

    /** 保存旋转限制开关（上下限位本身走 {@code /ptz/action} 的 limit-up / limit-down）。 */
    @Operation(summary = "保存旋转限制开关")
    @PostMapping("/ptz/limit")
    public ApiResponse<Void> saveLimit(@RequestParam String serial,
                                       @RequestParam(required = false) String code,
                                       @RequestBody DahuaPtzConfigService.LimitCfg cfg) {
        StpUtil.checkLogin();
        ptzConfigService.saveLimit(serial, code, cfg);
        return ApiResponse.ok(null);
    }

    /**
     * 保存空闲动作（设备 {@code IdleMotion}）。
     *
     * <p>注意与 {@link #saveAutoMovement} 的区别：
     * <ul>
     *   <li>空闲动作 = 设备「閒置動作」页，配置名 {@code IdleMotion}，等待时间单位<b>分钟</b>，1~60</li>
     *   <li>定时任务 = 设备「已排定工作」页，配置名 {@code PtzAutoMovement}，按时间段触发</li>
     * </ul>
     * 两者是设备上两份独立配置，不能混用。
     */
    @Operation(summary = "保存空闲动作（IdleMotion，分钟级）")
    @PostMapping("/ptz/idle")
    public ApiResponse<Void> saveIdleMotion(@RequestParam String serial,
                                            @RequestParam(required = false) String code,
                                            @RequestBody DahuaPtzConfigService.IdleMotionCfg cfg) {
        StpUtil.checkLogin();
        ptzConfigService.saveIdleMotion(serial, code, cfg);
        return ApiResponse.ok(null);
    }

    /** 保存一条定时任务（设备 PtzAutoMovement，按时间段触发）。 */
    @Operation(summary = "保存定时任务（PtzAutoMovement，按时间段触发）")
    @PostMapping("/ptz/automovement")
    public ApiResponse<Void> saveAutoMovement(@RequestParam String serial,
                                              @RequestParam(required = false) String code,
                                              @RequestBody AutoMovementRequest req) {
        StpUtil.checkLogin();
        ptzConfigService.saveAutoMovement(serial, code, req.index(), req.enable(), req.function(),
                req.presetId(), req.tourId(), req.patternId(), req.scanId(), req.homingSeconds(),
                req.ranges());
        return ApiResponse.ok(null);
    }

    /**
     * 定时任务的入参。
     *
     * <p>为什么允许字段为空：不同动作类型只需要一个目标号
     * （预置位 / 巡航组 / 巡迹 / 线扫各一个），
     * 缺省字段表示「保持设备现值」而不是「清空」。
     *
     * <p><b>target 字段的对应关系</b>（设备 {@code PtzAutoMovement} 是四个独立字段）：
     * <table>
     *   <tr><td>Preset</td><td>presetId</td></tr>
     *   <tr><td>Tour</td><td>tourId</td></tr>
     *   <tr><td>Pattern</td><td>patternId</td></tr>
     *   <tr><td>Scan</td><td>scanId</td></tr>
     * </table>
     *
     * @param ranges 每日执行时段，<b>逐段带星期</b>；{@code null} = 不改时段。
     *               设备每天最多 6 段，同一星期可以有多段（如上午 + 下午）。
     *               早期这里是 {@code days + from + to}（7 天共用一个起止），
     *               表达不了「每天不同时段」。
     */
    public record AutoMovementRequest(int index, boolean enable, String function,
                                      Integer presetId, Integer tourId, Integer patternId,
                                      Integer scanId, Integer homingSeconds,
                                      List<DahuaPtzConfigService.DayRange> ranges) {
    }

    // ==================== 相机参数 ====================

    /** 读取参数能力位（前端据此动态生成表单，隐藏型号不支持的项）。 */
    @Operation(summary = "相机参数能力位（19 项是否可用 + 当前值）")
    @GetMapping("/cameraconfig/capabilities")
    public ApiResponse<List<DahuaCameraConfigService.Capability>> configCapabilities(
            @RequestParam String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        return ApiResponse.ok(cameraConfig.capabilities(serial, channel));
    }

    /** 读取全部参数当前值。 */
    @Operation(summary = "读取相机参数")
    @GetMapping("/cameraconfig")
    public ApiResponse<Map<String, Object>> readConfig(
            @RequestParam String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        return ApiResponse.ok(cameraConfig.read(serial, channel));
    }

    /**
     * 修改相机参数。
     *
     * <p>请求体是 {@code {"brightness": 60, "contrast": 45}} 这样的键值对。
     * 键必须是 {@code /cameraconfig/capabilities} 返回过的 key，否则报错 ——
     * 拼错的 key 静默忽略会让人以为改成功了。
     */
    @Operation(summary = "修改相机参数")
    @PostMapping("/cameraconfig")
    public ApiResponse<Map<String, Object>> writeConfig(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestBody Map<String, Object> values) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        DahuaCameraConfigService.WriteResult r = cameraConfig.write(serial, channel, values);
        /*
         * 三种结果必须分开告诉用户，不能一律「保存成功」：
         *   全部生效            → saved
         *   写了但设备没接受    → saved=false + 逐个列出被拒的参数
         *   需要重启才生效      → saved + needRestart
         */
        if (!r.notEffective().isEmpty()) {
            /*
             * hints 里放的是「设备侧规则」造成的原因与解法（如 EIS 与智慧移动侦测互斥）。
             * 有 hint 时优先展示它 —— 比「该型号可能把这些项做成只读」精确得多，
             * 用户照着做就能解决。
             */
            String note = r.hints().isEmpty()
                    ? "设备接受了指令但没有应用这些参数（回读仍是原值）。"
                            + "该型号可能把这些项做成了只读，或需在设备网页/ConfigTool 里设置"
                    : String.join("；", r.hints().values());
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("saved", false);
            body.put("needRestart", r.needRestart());
            body.put("rejected", r.notEffective());
            body.put("note", note);
            if (!r.hints().isEmpty()) {
                body.put("hints", r.hints());
            }
            return ApiResponse.ok(body);
        }
        return ApiResponse.ok(Map.of(
                "saved", true,
                "needRestart", r.needRestart(),
                "note", r.needRestart()
                        ? "参数已写入，需要重启设备才生效"
                        : "参数已写入并回读确认生效"));
    }

    // ==================== 喊话 / 对讲 ====================

    /** 开始对讲（同一设备同时只能有一人，第二人会收到明确提示）。 */
    @Operation(summary = "开始对讲/喊话")
    @PostMapping("/talk/start")
    public ApiResponse<TalkSessionVo> talkStart(@RequestParam String serial,
                                               jakarta.servlet.http.HttpServletRequest request) {
        String owner = String.valueOf(StpUtil.getLoginId());
        var s = talkService.start(serial, owner);
        TalkSessionVo vo = new TalkSessionVo();
        vo.setTalkId(s.talkId());
        vo.setDeviceId(s.deviceId());
        vo.setOwner(s.owner());
        // 音频口径由设备侧协商决定（8 kHz/16 bit/单声道裸 PCM），前端据此重采样
        vo.setSampleRate(DahuaTalkService.SAMPLE_RATE);
        vo.setChannels(DahuaTalkService.CHANNELS);
        vo.setFormat(DahuaTalkService.FORMAT);
        vo.setWsUrl(talkWsUrl(request, s.talkId()));
        return ApiResponse.ok(vo);
    }

    /**
     * 拼下行音频的 WebSocket 地址。
     *
     * <p>把 token 放 query 是<b>唯一可行</b>的做法：浏览器
     * {@code new WebSocket()} 不支持自定义请求头，登录态没法走 Authorization。
     * 代价是 token 会写进 nginx access log —— 因此只给「听」，且会话结束即失效；
     * 需要更严的部署可以改成一次性短票据（换成 {@code /talk/start} 返回的随机串）。
     */
    private static String talkWsUrl(jakarta.servlet.http.HttpServletRequest request, String talkId) {
        boolean https = "https".equalsIgnoreCase(request.getScheme());
        int port = request.getServerPort();
        boolean defaultPort = (https && port == 443) || (!https && port == 80);
        StringBuilder sb = new StringBuilder(https ? "wss://" : "ws://")
                .append(request.getServerName());
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        sb.append(ctx).append("/api/v1/dahua/talk/audio")
                .append("?talkId=").append(talkId)
                .append("&token=").append(StpUtil.getTokenValue());
        return sb.toString();
    }

    /**
     * 发送一帧音频。
     *
     * <p>请求体是<b>裸音频字节</b>（Content-Type: application/octet-stream），
     * 不是 JSON、更不是 base64 —— base64 会让体积涨 33%，
     * 而喊话是实时流，多出来的这部分是纯浪费。
     *
     * <p>编码必须是<b>裸 PCM，8 kHz / 16 bit / 单声道</b>（与 {@code /talk/start} 返回的
     * {@code sampleRate/format} 一致）。MP3/AAC 发不出去；G711 也不行 ——
     * 服务端已把设备侧编码固定成 PCM，送别的格式设备只会当噪音播出来。
     */
    @Operation(summary = "发送音频帧（裸 PCM/G711 字节）")
    @PostMapping(value = "/talk/send", consumes = "application/octet-stream")
    public ApiResponse<Void> talkSend(@RequestParam String talkId,
                                      @RequestBody byte[] pcm) {
        StpUtil.checkLogin();
        if (pcm == null || pcm.length == 0) {
            throw BizException.badRequest("音频数据为空");
        }
        talkService.send(talkId, pcm);
        return ApiResponse.ok();
    }

    /** 结束对讲（幂等）。 */
    @Operation(summary = "结束对讲/喊话")
    @PostMapping("/talk/stop")
    public ApiResponse<Void> talkStop(@RequestParam String talkId) {
        StpUtil.checkLogin();
        talkService.close(talkId);
        return ApiResponse.ok();
    }

    /** 该设备当前谁在喊话（{@code null} 表示空闲）。 */
    @Operation(summary = "查询设备对讲占用状态")
    @GetMapping("/talk/busy")
    public ApiResponse<Map<String, Object>> talkBusy(@RequestParam String serial) {
        StpUtil.checkLogin();
        String owner = talkService.busyOwner(serial);
        return ApiResponse.ok(Map.of("busy", owner != null,
                "owner", owner == null ? "" : owner));
    }

    // ==================== 录像下载 ====================

    /** 发起录像下载（异步，立即返回任务 ID）。 */
    @Operation(summary = "发起设备录像下载")
    @PostMapping("/record/download")
    public ApiResponse<DahuaDownloadService.TaskView> download(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam String starttime,
            @RequestParam String endtime) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        return ApiResponse.ok(downloadService.start(serial, channel, starttime, endtime));
    }

    /** 下载任务列表。 */
    @Operation(summary = "录像下载任务列表")
    @GetMapping("/record/download/list")
    public ApiResponse<List<DahuaDownloadService.TaskView>> downloadList() {
        StpUtil.checkLogin();
        return ApiResponse.ok(downloadService.list());
    }

    /** 单个任务状态。 */
    @Operation(summary = "录像下载任务状态")
    @GetMapping("/record/download/info")
    public ApiResponse<DahuaDownloadService.TaskView> downloadInfo(@RequestParam String taskId) {
        StpUtil.checkLogin();
        return ApiResponse.ok(downloadService.get(taskId));
    }

    /** 移除任务记录（不删已下载文件）。 */
    @Operation(summary = "移除录像下载任务")
    @PostMapping("/record/download/remove")
    public ApiResponse<Void> downloadRemove(@RequestParam String taskId) {
        StpUtil.checkLogin();
        downloadService.remove(taskId);
        return ApiResponse.ok();
    }

    // ==================== 录像计划 ====================

    /**
     * 读设备侧录像计划。
     *
     * <p>回答的是「设备到底在什么时段录像」—— 与前端「录像1」这类平台侧录像任务
     * 是两件事。设备侧录像完全由这个计划决定，界面上的开关只影响平台录制。
     */
    @Operation(summary = "读设备录像计划")
    @GetMapping("/record/plan")
    public ApiResponse<List<DahuaRecordPlanService.Slot>> recordPlan(
            @RequestParam String serial,
            @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        return ApiResponse.ok(recordPlanService.read(serial, channel));
    }

    /**
     * 开 / 关设备侧录像（改录像计划）。
     *
     * <p>国标 {@code Record}/{@code StopRecord} 的等价实现 —— 大华没有独立录像开关，
     * 设备侧录像由 {@code Record} 配置项的时间段决定。
     */
    @Operation(summary = "开/关设备录像计划")
    @PostMapping("/record/plan")
    public ApiResponse<List<DahuaRecordPlanService.Slot>> setRecordPlan(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "true") Boolean enabled) {
        StpUtil.checkLogin();
        String channel = (code == null || code.isBlank()) ? serial : code;
        return ApiResponse.ok(recordPlanService.setRecording(serial, channel, enabled));
    }
}
