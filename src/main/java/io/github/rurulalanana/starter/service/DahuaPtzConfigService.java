package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大华云台的<b>配置类</b>能力：巡航组、线扫、巡迹、速度、空闲动作、定时任务、限位。
 *
 * <h3>为什么走 CGI 而不是 SDK 的 JSON 配置接口</h3>
 * 实测这台设备对 {@code CLIENT_GetNewDevConfig("PtzTour")} / {@code ("PtzAutoMovement")}
 * 一律返回失败（{@code PtzPreset} 也一样），但同样的配置项通过设备自己的
 * {@code configManager.cgi} 能正常读写 —— 设备 Web 界面用的就是这条路。
 * 所以这里统一走 {@link DahuaCgiAccess}，SDK 只负责「动作」（转、扫、重启）。
 *
 * <h3>配置项对照（2026-11 在 192.168.10.156 逐页核对设备「PTZ設置」菜单）</h3>
 * <pre>
 * 巡航群組    PtzTour[ch][route].Enable / Name / Path / Presets[slot][0..2]
 * 線性掃描    AutoScan[ch][idx].LeftEnable / RightEnable / Left|RightPosition[3]
 *                                 / Left|RightZoomMapValue / Left|RightFocusMapValue
 *                                 / ScanDirection / ScanSpeed
 * 巡跡        AutoPattern[ch][idx].Enable / Name
 * PTZ速度     PtzSpeed[ch].PtzSpeed / PatternSpeed
 * 閒置動作    IdleMotion[ch].Enable / Function / PresetId / TourId / PatternId
 *                                  / ScanId / Timer(分钟)
 * 已排定工作  PtzAutoMovement[ch][idx].Enable / Function / PresetId / TourId / PatternId
 *                                  / ScanId / AutoHoming.Time(秒) / TimeSection[7][6]
 * 旋轉限制    PtzLimit[ch].Enable / HorizontalLimitEnable
 * 当前运行    PtzMovement[ch].Function / Index / TaskName
 * </pre>
 *
 * <h3>实测到的取值约束（不要照抄「文档上看起来合理」的数）</h3>
 * <ul>
 *   <li><b>PtzSpeed 只有 0~4</b>（写 5 直接 Bad Request）。设备 UI 是「低/中/高」三档滑块。</li>
 *   <li><b>巡航点速度 Presets[slot][2] 是 0~10</b>（设备 UI 下拉 1~10），不是 1~8。</li>
 *   <li><b>巡航点停留 Presets[slot][1] 是 0~3600</b> 秒（写 0 会被设备改回默认 15）。</li>
 *   <li><b>Presets[slot][0] 是 0-based 的预置位下标</b>：
 *       写 {@code 1} 设备界面显示「預置點2」。平台对外一律用 1-based，读写各做一次 ±1。</li>
 *   <li><b>AutoScan.ScanSpeed 是 0~7</b>（写 8 Bad Request）。</li>
 *   <li><b>IdleMotion.Timer 是 1~60 分钟</b>（写 0 或 61 都 Bad Request）。</li>
 *   <li><b>AutoHoming.Time 单位是秒</b>，UI 提示 5~86400，但设备不做校验（0 和 86401 都收）。</li>
 *   <li><b>PtzSpeed 只能通过 RPC2 写</b>：CGI 写 {@code PtzSpeed[0].PtzSpeed=4} 返回 OK 但值不变。
 *       这是本类唯一走 RPC2 的字段，见 {@link #saveSpeed}。</li>
 * </ul>
 *
 * <p>通道下标与 SDK 一致：IPC 是 0，NVR 是 1~N。不要 +1，否则会写到不存在的通道上
 * （设备对越界下标通常静默忽略，表现为「保存成功但没生效」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DahuaPtzConfigService {

    /** 巡航组每条线的固定槽位数（设备侧就是 32 个）。 */
    private static final int TOUR_SLOTS = 32;
    /** 空槽的默认值（与设备出厂一致）。 */
    private static final String EMPTY_PRESET = "-1";
    private static final String DEFAULT_STAY = "15";
    private static final String DEFAULT_SPEED = "7";

    // ---- 设备实测边界，改之前先看类注释 ----
    /**
     * 手动速度 {@code PtzSpeed[ch].PtzSpeed}：设备「PTZ速度」页三档 0=低/1=中/2=高。
     *
     * <p>CGI 写 3/4 也接受，但设备界面滑块会钳到 2，所以对外只到 2。
     */
    private static final int PTZ_SPEED_MIN = 0;
    private static final int PTZ_SPEED_MAX = 2;
    /**
     * 巡迹速度 {@code PtzSpeed[ch].PatternSpeed}：<b>设备界面没有入口</b>，出厂 5。
     *
     * <p>平台不展示也不主动改它（前端不传 = 保持现值）。这里只用于校验
     * 「万一传了」的情况，区间取得宽松一些，避免把固件自己的值卡掉。
     */
    private static final int PATTERN_SPEED_MIN = 0;
    private static final int PATTERN_SPEED_MAX = 8;
    /**
     * 水平旋转速度：设备界面滑块 <b>1~8</b>，内部 {@code AutoPan[ch].PanSpeed} <b>0~7</b>。
     *
     * <p>⚠️ <b>界面值 = 内部值 + 1</b>。实测（192.168.10.156 / fw 3.201.0000007.1.R）：
     * 设备界面滑块拖到 8 时发的是 {@code PanSpeed:7}；内部存 2 时界面显示 3。
     * 所以设备界面的 {@code aria-valuemax=8} 不是「假边界」，而是<b>显示口径</b>。
     *
     * <p>这里对外统一用<b>界面口径 1~8</b>（和用户看到的设备界面一致），
     * 写设备时减 1。直接拿内部值当界面值会让平台显示比设备少 1。
     *
     * <p>与 {@link #PTZ_SPEED_MIN}/{@link #PTZ_SPEED_MAX}（0~4）是<b>两个不同字段</b>。
     */
    private static final int AUTO_PAN_SPEED_MIN = 1;
    private static final int AUTO_PAN_SPEED_MAX = 8;
    /** 巡航点速度 Presets[slot][2]：设备 UI 下拉 1~10。 */
    private static final int TOUR_POINT_SPEED_MIN = 1;
    private static final int TOUR_POINT_SPEED_MAX = 10;
    /**
     * 巡航点停留秒。
     *
     * <p><b>下限是 15，不是 1</b>：实测写入 1~14 时设备不报错，但会把值静默改回默认的 15
     * （写入 12 读回 15），16 及以上原样保留。写成 1 会让用户填的值悄悄失效，
     * 所以下限收到 15 并在保存前直接拒绝，而不是让它「看起来保存成功」。
     */
    private static final int TOUR_STAY_MIN = 15;
    private static final int TOUR_STAY_MAX = 3600;
    /** 线扫速度 AutoScan.ScanSpeed 实测 0~7。 */
    private static final int SCAN_SPEED_MIN = 0;
    private static final int SCAN_SPEED_MAX = 7;
    /** 巡迹名称长度上限（设备侧未声明，取一个保守值）。 */
    private static final int PATTERN_NAME_MAX = 32;
    /**
     * 巡迹停留秒：{@code PatternPlayDetail[ch][idx].StartPatternPlayDelay}（毫秒）换算。
     *
     * <p>范围取自设备能力位 {@code PtzPattern:{DurationMin:0, DurationMax:3600}}，
     * 设备输入框也把 99999 钳到 3600。出厂默认值 <b>15 秒</b>（存 15000 毫秒）。
     */
    private static final int PATTERN_STAY_MIN = 0;
    private static final int PATTERN_STAY_MAX = 3600;
    /** 空闲动作等待时间，单位分钟，实测 1~60。 */
    private static final int IDLE_TIMER_MIN = 1;
    private static final int IDLE_TIMER_MAX = 60;
    /** 定时任务归位秒数，UI 提示 5~86400（设备不校验，按 UI 收紧）。 */
    private static final int HOMING_MIN = 5;
    private static final int HOMING_MAX = 86400;
    /** 每星期最多 6 个时间段（设备 TimeSection[d][0..5]）。 */
    private static final int DAY_SECTIONS = 6;
    /**
     * 未启用时段的占位值（设备出厂就是 {@code 0 00:00:00-23:59:59}）。
     *
     * <p>不启用时也要给一个合法的完整区间：写空串或 00:00-00:00 设备会当成非法值。
     */
    private static final String EMPTY_SECTION = "00:00:00-23:59:59";

    private static final Pattern TOUR_POINT = Pattern.compile(
            "PtzTour\\[(\\d+)]\\[(\\d+)]\\.Presets\\[(\\d+)]\\[(\\d+)]");
    private static final Pattern TOUR_FIELD = Pattern.compile(
            "PtzTour\\[(\\d+)]\\[(\\d+)]\\.(Enable|Name|Path)");
    private static final Pattern AUTO_FIELD = Pattern.compile(
            "PtzAutoMovement\\[(\\d+)]\\[(\\d+)]\\.(Enable|Function|PresetId|TourId|PatternId|ScanId|AutoHoming\\.Time)");
    private static final Pattern AUTO_SECTION = Pattern.compile(
            "PtzAutoMovement\\[(\\d+)]\\[(\\d+)]\\.TimeSection\\[(\\d+)]\\[(\\d+)]");

    private final DahuaCapabilityService capability;

    // ==================== 数据模型 ====================

    /** 巡航点：预置位号（1-based）+ 停留秒 + 速度。 */
    public record TourPoint(int preset, int stay, int speed) {
    }

    /** 一条巡航线。{@code route} 是 1-based 的业务线号。 */
    public record Tour(int route, String name, boolean enable, List<TourPoint> points) {
    }

    /** 一条线扫。{@code scanId} 是 1-based 的业务编号。 */
    public record Scan(int scanId, boolean leftSet, boolean rightSet, int speed,
                       String direction, String leftPosition, String rightPosition) {
    }

    /**
     * 一条巡迹。{@code patternId} 是 1-based 的业务编号。
     *
     * <p><b>停留时间在另一个配置项里</b>：设备「巡跡」页的「停留時間(S)」列实测存在
     * {@code PatternPlayDetail[ch][idx].StartPatternPlayDelay}，<b>单位毫秒</b>
     * （设备 UI 显示 15 秒 = 存 15000）。出厂默认 15000。
     * 范围取自能力位 {@code PtzPattern: {DurationMin:0, DurationMax:3600}}，
     * 设备输入框也把 99999 钳到 3600。
     *
     * <p>⚠️ 早期这里写的是「设备没有停留时间字段」，那是因为只读了 {@code AutoPattern}
     * （它确实只有 Enable/Name）。真正存停留时间的是 {@code PatternPlayDetail}。
     *
     * @param staySeconds 播放前停留秒数，0~3600
     */
    public record PatternCfg(int patternId, String name, boolean enable, int staySeconds) {
    }

    /**
     * 云台速度（{@code PtzSpeed[ch]}）。
     *
     * <h3>设备「PTZ速度」页只有<b>一个</b>三档滑块</h3>
     * 实测（192.168.10.156 / fw 3.201.0000007.1.R）：页面上只有
     * {@code 低 / 中 / 高} 三个刻度，滑块 {@code aria-valuemin=0, aria-valuemax=2}，
     * 而存储字段 {@code PtzSpeed[ch].PtzSpeed} 与滑块取值<b>一一对应</b>：
     * <pre>
     *   滑块 0 = 低   ←→  PtzSpeed=0
     *   滑块 1 = 中   ←→  PtzSpeed=1
     *   滑块 2 = 高   ←→  PtzSpeed=2
     * </pre>
     * 写成 4 时设备界面把滑块钳到 2（显示「高」），所以 CGI 虽然接受 0~4，
     * <b>界面上只有 0~2 有意义</b>。
     *
     * <p>⚠️ 早期平台自创了一套「0~4 五档、低=0/中=2/高=4」的映射，
     * 于是设备存 1（中）时平台显示成「低」—— 用户看到的就是「回显不对」。
     *
     * <p>{@code PatternSpeed}（巡迹回放速度）<b>不在这一页</b>，设备界面没有任何入口，
     * 出厂值 5 由固件自己维护。所以 {@code patternSpeed} 传 {@code null} 表示
     * 「保持设备现值」，平台不展示也不改动它 —— 硬塞一个自以为什么值反而会把 5 改掉。
     *
     * @param ptzSpeed     手动速度，<b>0=低 / 1=中 / 2=高</b>
     * @param patternSpeed 巡迹回放速度；{@code null} = 保持设备现值（前端不传）
     */
    public record SpeedCfg(int ptzSpeed, Integer patternSpeed) {
    }

    /**
     * 水平旋转速度（设备「水準旋轉」页的「旋轉速度」滑块）。
     *
     * <p><b>不要把它当成 {@link SpeedCfg#ptzSpeed}</b>：两者是<b>不同的配置项</b>。
     * 实测（192.168.10.156 / fw 3.201.0000007.1.R）：
     * <ul>
     *   <li>「水準旋轉」页滑块 → {@code AutoPan[ch].PanSpeed}，范围 <b>1~8</b>，
     *       每拖动一次立即 RPC2 {@code configManager.setConfig} 落盘，没有「应用」按钮；</li>
     *   <li>「PTZ速度」页 → {@code PtzSpeed[ch].PtzSpeed}，范围 <b>0~4</b>（低/中/高）。</li>
     * </ul>
     * 早期平台把水平旋转页做成「只读显示 + 去速度页调整」，那是不对的：
     * 设备上这一页本来就能直接调，而且改的是另一个字段，去「速度」页根本调不到它。
     *
     * @param panSpeed 水平旋转速度 1~8
     */
    public record AutoPanCfg(int panSpeed) {
    }

    /**
     * 空闲动作（设备 {@code IdleMotion}，与定时任务不是同一份配置）。
     *
     * @param timerMinutes 空闲多少分钟后执行，1~60
     */
    public record IdleMotionCfg(boolean enable, String function,
                                int presetId, int tourId, int patternId, int scanId,
                                int timerMinutes) {
    }

    /**
     * 一条定时任务（设备 {@code PtzAutoMovement}）。
     *
     * @param index        规则下标 0~3（能力位 TaskNum=4）
     * @param function     动作类型 None/Preset/Tour/Pattern/Scan
     * @param tourId       目标巡航组号（function=Tour 时有效）
     * @param homingSeconds 任务结束后归位秒数
     * @param timeSections 时间段原文，形如 {@code 1 08:00:00-18:00:00}，首位是启用标记
     */
    public record AutoMovement(int index, boolean enable, String function, int presetId,
                               int tourId, int patternId, int scanId, int homingSeconds,
                               List<String> timeSections) {
    }

    /**
     * 限位开关状态（边界本身只能用 SDK/RPC2 动作设置）。
     *
     * <p>{@code upBoundary} / {@code downBoundary} 是设备回读的只读值：
     * 这台 IPC 的 {@code PtzLimit} 只暴露 {@code UpLimit.Boundary}，
     * 没有 {@code DownLimit}（设备固件确实没下发该字段），所以下限位置显示为「未设置」——
     * 这是设备的真实能力，不是平台没实现。
     */
    public record LimitCfg(boolean enable, boolean horizontalEnable,
                           String upBoundary, String downBoundary) {
    }

    /**
     * 设备当前正在执行的动作（{@code PtzMovement}）。
     *
     * <h3>真实编码（2026-11 用设备自己的界面逐项验证）</h3>
     * <table border="1">
     *   <tr><th>设备状态</th><th>Function</th><th>Index</th><th>TaskName</th></tr>
     *   <tr><td>空闲</td><td>{@code None}</td><td>0</td><td>{@code Unknown}</td></tr>
     *   <tr><td>巡航 1 运行</td><td>{@code Tour}</td><td><b>0</b></td><td>{@code Manual}</td></tr>
     *   <tr><td>巡迹 2 运行</td><td>{@code Pattern}</td><td><b>1</b></td><td>{@code Manual}</td></tr>
     *   <tr><td>线扫 1 运行</td><td>{@code Scan}</td><td><b>0</b></td><td>{@code Manual}</td></tr>
     *   <tr><td>水平旋转运行</td><td>{@code AutoPan}</td><td>0</td><td>{@code Manual}</td></tr>
     * </table>
     *
     * <p>所以：
     * <ul>
     *   <li><b>{@code TaskName} 才是「在不在跑」</b>：{@code Manual} = 运行中，
     *       {@code Unknown} = 空闲。不要用 {@code Index > 0} 判断 ——
     *       0 是合法下标（第 1 条）。</li>
     *   <li><b>{@code Function} 是运行类型</b>，{@code Index} 是该类型内
     *       <b>0-based 下标</b>（业务编号 = {@code index + 1}）。</li>
     *   <li>{@code Function=Preset} <b>不算运行</b>：那是「转向/停在预置位」的残留态，
     *       巡航跑完后设备会长期留在这个值上，而设备自己的开关其实是关的。</li>
     * </ul>
     *
     * <p>⚠️ 早期这里写着「无论是否在巡航，{@code PtzMovement[0]} 恒为
     * {@code Function=Tour, Index=0}」，那是观测时恰好有一条巡航在跑（Index=0 = 第 1 条线）
     * 造成的误判。前端据此要求 {@code index > 0}，结果<b>第 1 条永远显示未运行、
     * 第 2 条明明在跑却显示未运行</b>。
     *
     * <p>设备同一时刻只能跑一个动作，所以这个结构也可用于「互斥」判断：
     * 有任何一个在跑时，其它动作的开关必须是禁用态。
     *
     * @param running  是否真的在运行（{@code TaskName != Unknown}）
     * @param function 运行类型 None/Tour/Pattern/Scan/AutoPan
     * @param index    该类型内的 <b>0-based</b> 下标
     * @param taskName 原始 TaskName（Manual / Unknown）
     */
    public record RunningCfg(boolean running, String function, int index, String taskName) {
    }

    /** 一次性返回的云台配置快照。 */
    public record PtzConfig(List<Tour> tours, List<Scan> scans, List<PatternCfg> patterns,
                            SpeedCfg speed, AutoPanCfg autoPan, IdleMotionCfg idle,
                            List<AutoMovement> autoMovements,
                            LimitCfg limit, RunningCfg running) {
    }

    // ==================== 读 ====================

    /**
     * 读全部云台配置。
     *
     * <p>八项分别一次 HTTP：设备没有「批量读多个 name」的入口，
     * 合并成一次请求需要 {@code name=PtzTour,PtzSpeed,...} 这种写法，
     * 部分固件不认，所以宁可多几次往返（内网每次几十毫秒）。
     */
    public PtzConfig read(String deviceId, String channelCode) {
        int ch = cfgChannel(deviceId, channelCode);
        return new PtzConfig(
                parseTours(readOrEmpty(deviceId, "PtzTour"), ch),
                parseScans(readOrEmpty(deviceId, "AutoScan"), ch),
                parsePatterns(readOrEmpty(deviceId, "AutoPattern"),
                        readOrEmpty(deviceId, "PatternPlayDetail"), ch),
                parseSpeed(readOrEmpty(deviceId, "PtzSpeed"), ch),
                parseAutoPan(readOrEmpty(deviceId, "AutoPan"), ch),
                parseIdle(readOrEmpty(deviceId, "IdleMotion"), ch),
                parseAuto(readOrEmpty(deviceId, "PtzAutoMovement"), ch),
                parseLimit(readOrEmpty(deviceId, "PtzLimit"), ch),
                parseRunning(readOrEmpty(deviceId, "PtzMovement"), ch));
    }

    /** 只读巡航组（UI 打开「巡航」页时用，避免多读几项）。 */
    public List<Tour> readTours(String deviceId, String channelCode) {
        return parseTours(readOrEmpty(deviceId, "PtzTour"), cfgChannel(deviceId, channelCode));
    }

    /**
     * 只读「当前运行状态」（{@code PtzMovement}）—— 单次 CGI，前端可轮询。
     *
     * <p><b>为什么要单独一个方法</b>：运行状态是易变的（启停只在下发那一刻改变），
     * 而 {@link #read} 要读 9 个配置项、上百 KB。让前端为了刷新几个开关去拉整份配置
     * 既慢又会给设备加压。这个方法只读一项，几千字节，3 秒轮询没压力。
     *
     * <p>有了轮询，界面上的「执行」开关就不需要靠本地记忆去猜 ——
     * 设备上被别处启停、巡航自己跑完，界面都会自动跟上。
     */
    public RunningCfg readRunning(String deviceId, String channelCode) {
        return parseRunning(readOrEmpty(deviceId, "PtzMovement"), cfgChannel(deviceId, channelCode));
    }

    // ==================== 写：巡航组 ====================

    /**
     * 保存一条巡航线（整条覆盖）。
     *
     * <p><b>为什么整条写而不是逐点追加</b>：设备侧就是 32 个固定槽位，
     * 「追加一个点」实际是「找到第一个空槽写进去」，逐点操作在并发下会互相踩。
     * 整条提交的语义清晰，UI 也更好做（所见即所得）。
     */
    public void saveTour(String deviceId, String channelCode, Tour tour) {
        if (tour == null) {
            throw BizException.badRequest("缺少巡航线内容");
        }
        int route = tour.route();
        if (route < 1 || route > 64) {
            throw BizException.badRequest("巡航线号必须在 1~64 之间：" + route);
        }
        int ch = cfgChannel(deviceId, channelCode);
        String prefix = "PtzTour[" + ch + "][" + (route - 1) + "]";

        List<TourPoint> points = tour.points() == null ? List.of() : tour.points();
        validateTourPoints(route, points);

        Map<String, String> kv = new LinkedHashMap<>();
        /*
         * 写 Enable —— 它<b>不是</b>「巡航启停」，而是「这条巡航在设备列表里存不存在」。
         *
         * <p>实测（抓设备自己的请求）：点「新增巡航群組」时设备发的是
         * {@code setConfig PtzTour} 带 {@code "Enable": true}；「清空」置回 false。
         * 设备「巡航群組」页<b>只列出 Enable=true 的行</b>，Enable=false 的即使有预置点也不显示。
         *
         * <p>所以早期「完全不写 Enable」是错的：平台保存完预置点后，
         * 设备界面上那条巡航根本看不见，也就没法在设备上启停它。
         *
         * <p>「執行」开关仍然只走 {@code ptz.startTour / ptz.stopTour}，与这个字段无关。
         */
        kv.put(prefix + ".Enable", points.isEmpty() ? "false" : "true");
        kv.put(prefix + ".Name", tour.name() == null || tour.name().isBlank()
                ? "巡航群組" + route : tour.name().trim());
        for (int slot = 0; slot < TOUR_SLOTS; slot++) {
            if (slot < points.size()) {
                TourPoint p = points.get(slot);
                // 业务层是 1-based 预置位号，设备存 0-based
                kv.put(prefix + ".Presets[" + slot + "][0]", String.valueOf(Math.max(0, p.preset() - 1)));
                kv.put(prefix + ".Presets[" + slot + "][1]", String.valueOf(p.stay()));
                kv.put(prefix + ".Presets[" + slot + "][2]", String.valueOf(p.speed()));
            } else {
                kv.put(prefix + ".Presets[" + slot + "][0]", EMPTY_PRESET);
                kv.put(prefix + ".Presets[" + slot + "][1]", DEFAULT_STAY);
                kv.put(prefix + ".Presets[" + slot + "][2]", DEFAULT_SPEED);
            }
        }
        write(deviceId, kv);

        /*
         * 写后回读校验名称。
         *
         * <p>实测这台 IPC（fw 3.201.0000007.1.R）对 {@code PtzTour[ch][route].Name}
         * <b>只读不写</b>：CGI setConfig 直接 HTTP 400，SDK SetNewDevConfig 回
         * {@code code=0} 但值不变。预置点（Presets）能写，Name 不能。
         *
         * <p>不校验的后果是「接口回 ok、界面刷新后名字变回巡航群組N」，
         * 用户会以为是平台没保存。所以这里回读一次：名称没写进去就明确报错，
         * 让「设备不支持」当场可见，而不是让用户反复重试。
         */
        verifyTourName(deviceId, channelCode, route, kv.get(prefix + ".Name"));

        log.info("巡航线已保存: device={} channel={} route={} enable={} points={}",
                deviceId, channelCode, route, tour.enable(), points.size());
    }

    /**
     * 回读校验巡航线名称是否真的落到设备上。
     *
     * <p>只校验名称，不校验预置点：预置点写入是可靠的（实测多次往返一致），
     * 而名称在这台设备上写不进去。把不支持的项单独报出来，用户才知道
     * 「哪些存住了、哪些是设备限制」。
     */
    private void verifyTourName(String deviceId, String channelCode, int route, String expect) {
        if (expect == null || expect.isBlank()) {
            return;
        }
        String actual;
        try {
            actual = readTours(deviceId, channelCode).stream()
                    .filter(t -> t.route() == route)
                    .map(Tour::name)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // 回读失败不该把已经成功的写入判成失败
            log.warn("巡航线名称回读失败，跳过校验: device={} route={} {}", deviceId, route, e.getMessage());
            return;
        }
        if (actual != null && !expect.equals(actual)) {
            throw BizException.badRequest("设备未接受巡航组 " + route + " 的名称「" + expect
                    + "」，回读仍是「" + actual + "」。"
                    + "该型号固件不支持修改巡航组名称（PtzTour.Name 只读），"
                    + "预置点已保存成功。");
        }
    }

    /**
     * 保存前逐点校验。
     *
     * <p><b>为什么拒绝而不是夹取</b>：设备对越界值是<b>静默改写</b>（停留 12 → 15、
     * 速度 0 → 1），接口仍返回 OK。夹取同样返回 OK，用户会以为填的值生效了，
     * 直到去设备界面才发现不对。直接报错才能让问题当场暴露。
     */
    private static void validateTourPoints(int route, List<TourPoint> points) {
        if (points.size() > TOUR_SLOTS) {
            throw BizException.badRequest("巡航组 " + route + " 最多 " + TOUR_SLOTS
                    + " 个预置点，收到 " + points.size());
        }
        for (int i = 0; i < points.size(); i++) {
            TourPoint p = points.get(i);
            String at = "巡航组 " + route + " 第 " + (i + 1) + " 个预置点";
            if (p.preset() < 1 || p.preset() > 255) {
                throw BizException.badRequest(at + " 的预置位号必须在 1~255 之间：" + p.preset());
            }
            if (p.stay() < TOUR_STAY_MIN || p.stay() > TOUR_STAY_MAX) {
                throw BizException.badRequest(at + " 的停留时间必须在 " + TOUR_STAY_MIN + "~"
                        + TOUR_STAY_MAX + " 秒之间（设备对小于 " + TOUR_STAY_MIN
                        + " 秒的值会静默改回 " + TOUR_STAY_MIN + "）：" + p.stay());
            }
            if (p.speed() < TOUR_POINT_SPEED_MIN || p.speed() > TOUR_POINT_SPEED_MAX) {
                throw BizException.badRequest(at + " 的速度必须在 " + TOUR_POINT_SPEED_MIN + "~"
                        + TOUR_POINT_SPEED_MAX + " 之间：" + p.speed());
            }
        }
    }

    // ==================== 写：速度 ====================

    /**
     * 保存云台速度（设备「PTZ速度」页的单档三段速度）。
     *
     * <p>{@code ptzSpeed} 0=低 / 1=中 / 2=高，与设备滑块一一对应。
     *
     * <p>{@code patternSpeed} 为 {@code null} 时<b>保持设备现值</b>：
     * 设备界面没有「巡迹速度」入口，硬改它没有依据（出厂是 5）。
     */
    public void saveSpeed(String deviceId, String channelCode, SpeedCfg speed) {
        if (speed == null) {
            throw BizException.badRequest("缺少速度配置");
        }
        int ptz = speed.ptzSpeed();
        if (ptz < PTZ_SPEED_MIN || ptz > PTZ_SPEED_MAX) {
            throw BizException.badRequest("云台速度只能是 " + PTZ_SPEED_MIN + "=低 / 1=中 / "
                    + PTZ_SPEED_MAX + "=高：" + ptz);
        }
        int ch = cfgChannel(deviceId, channelCode);
        int pattern;
        if (speed.patternSpeed() == null) {
            // 没传就沿用设备现值，不要把固件自己的 5 覆盖成默认值
            pattern = parseSpeed(readOrEmpty(deviceId, "PtzSpeed"), ch).patternSpeed();
        } else {
            pattern = speed.patternSpeed();
            if (pattern < PATTERN_SPEED_MIN || pattern > PATTERN_SPEED_MAX) {
                throw BizException.badRequest("巡迹速度只能在 " + PATTERN_SPEED_MIN + "~"
                        + PATTERN_SPEED_MAX + " 之间：" + pattern);
            }
        }
        capability.setPtzSpeed(deviceId, ch, ptz, pattern);
        log.info("云台速度已保存: device={} channel={} ptz={} pattern={}({})",
                deviceId, channelCode, ptz, pattern,
                speed.patternSpeed() == null ? "沿用设备现值" : "指定");
    }

    /**
     * 保存水平旋转速度（设备「水準旋轉」页的「旋轉速度」）。
     *
     * <p>写的是 {@code AutoPan[ch].PanSpeed}，<b>不是</b> {@code PtzSpeed}。
     * 这两个字段在设备上是独立的：改「PTZ速度」页不会影响水平旋转速度，
     * 反之亦然。
     *
     * <p><b>为什么必须走 RPC2 而不是 CGI</b>：实测这台 IPC 的
     * {@code configManager.cgi?action=setConfig&name=AutoPan[0].PanSpeed} 一律返回
     * <b>HTTP 400</b>（值不变），而设备自己的 Web UI 改同一个字段是成功的 ——
     * 抓包看它走 {@code POST /RPC2} 的 {@code configManager.setConfig}。
     * 所以这里用 RPC2，并且<b>先读原结构再原样写回</b>（table 层级写错会把设备配置写坏）。
     *
     * <p>设备行为（实测）：拖动滑块时每动一下立刻下发一次 setConfig，没有「应用」按钮。
     * 平台做成「改完点保存」，落盘效果与设备一致，只是下发时机不同。
     */
    public void saveAutoPan(String deviceId, String channelCode, AutoPanCfg cfg) {
        if (cfg == null) {
            throw BizException.badRequest("缺少水平旋转速度配置");
        }
        int pan = cfg.panSpeed();
        if (pan < AUTO_PAN_SPEED_MIN || pan > AUTO_PAN_SPEED_MAX) {
            throw BizException.badRequest("水平旋转速度只能在 " + AUTO_PAN_SPEED_MIN + "~"
                    + AUTO_PAN_SPEED_MAX + " 之间（设备滑块共 "
                    + (AUTO_PAN_SPEED_MAX - AUTO_PAN_SPEED_MIN + 1) + " 档）：" + pan);
        }
        var session = capability.requireSession(deviceId);
        int httpPort = capability.httpPort();
        try {
            /*
             * 读回原结构再改字段。AutoPan 的 table 是 [{"PanSpeed":n}]（一维，按通道），
             * 但不要凭这个猜着构造 —— 直接拿设备给的对象改，层级永远对得上。
             */
            Object raw = capability.rpc().getConfigItem(session, httpPort, "AutoPan");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                throw BizException.badRequest("设备没有返回 AutoPan 配置，无法保存水平旋转速度");
            }
            List<Object> table = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    table.add(copy);
                } else {
                    table.add(item);
                }
            }
            // 通道下标：AutoPan 按通道一维排列
            int ch = cfgChannel(deviceId, channelCode);
            Object target = table.get(Math.min(ch, table.size() - 1));
            if (!(target instanceof Map<?, ?>)) {
                throw BizException.badRequest("AutoPan 结构异常，通道 " + ch + " 不是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) target;
            // 界面口径 1~8 → 设备内部 0~7
            row.put("PanSpeed", pan - 1);

            capability.rpc().setConfigItem(session, httpPort, "AutoPan", table);
            log.info("水平旋转速度已保存: device={} channel={} panSpeed={}",
                    deviceId, channelCode, pan);
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw capability.translate("保存水平旋转速度", e);
        }
    }

    // ==================== 写：空闲动作 ====================

    /**
     * 保存空闲动作（设备 {@code IdleMotion}）。
     *
     * <p>以前这个功能错误地写到了 {@code PtzAutoMovement}（那是「已排定工作」），
     * 表现为「平台上设了空闲动作，设备闲置后不动、设备界面里也看不到」。
     */
    public void saveIdleMotion(String deviceId, String channelCode, IdleMotionCfg cfg) {
        if (cfg == null) {
            throw BizException.badRequest("缺少空闲动作配置");
        }
        String fn = normalizeFunction(cfg.function());
        if (cfg.timerMinutes() < IDLE_TIMER_MIN || cfg.timerMinutes() > IDLE_TIMER_MAX) {
            throw BizException.badRequest("空闲时间间隔只能在 " + IDLE_TIMER_MIN + "~"
                    + IDLE_TIMER_MAX + " 分钟之间：" + cfg.timerMinutes());
        }
        int ch = cfgChannel(deviceId, channelCode);
        String prefix = "IdleMotion[" + ch + "]";
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(prefix + ".Enable", cfg.enable() ? "true" : "false");
        kv.put(prefix + ".Function", fn);
        kv.put(prefix + ".Timer", String.valueOf(cfg.timerMinutes()));
        if (!"None".equals(fn)) {
            // 设备要求四个目标号都非 0，只写用得到的那个会留下 0 导致不执行
            kv.put(prefix + ".PresetId", String.valueOf(Math.max(1, cfg.presetId())));
            kv.put(prefix + ".TourId", String.valueOf(Math.max(1, cfg.tourId())));
            kv.put(prefix + ".PatternId", String.valueOf(Math.max(1, cfg.patternId())));
            kv.put(prefix + ".ScanId", String.valueOf(Math.max(1, cfg.scanId())));
        }
        write(deviceId, kv);
        log.info("空闲动作已保存: device={} channel={} enable={} function={} timer={}min",
                deviceId, channelCode, cfg.enable(), fn, cfg.timerMinutes());
    }

    // ==================== 写：定时任务 ====================

    /**
     * 一段每日执行时段（设备 {@code TimeSection[day][slot]}）。
     *
     * <p>实测设备<b>每天支持 6 段互相独立的时间</b>（{@code TimeSection[7][6]}），
     * 每段首位是自己的启用标记，格式 {@code "1 08:00:00-12:00:00"}。
     * 例如星期一可以同时是 {@code 08:00-12:00} 和 {@code 13:00-18:00}。
     *
     * @param day  0=周日 … 6=周六（与设备下标一致）
     * @param from 起始 {@code HH:mm} 或 {@code HH:mm:ss}
     * @param to   结束 {@code HH:mm} 或 {@code HH:mm:ss}，必须晚于 from
     */
    public record DayRange(int day, String from, String to) {
    }

    /**
     * 保存一条定时任务（设备 {@code PtzAutoMovement}）。
     *
     * <h3>时间段为什么必须按天传</h3>
     * 早期接口是 {@code days + from + to}（7 天共用一个起止），而且<b>只写
     * {@code TimeSection[d][0]} 这一段</b>，其余 5 段保留设备原值。后果有两个：
     * <ul>
     *   <li>「每天不同时段」根本表达不了 —— 周一要 8-12、周二要 19-23 做不到；</li>
     *   <li>同一天想要两段（上午 + 下午）也做不到。</li>
     * </ul>
     * 现在按 {@link DayRange} 逐段传、逐段写，并把 7×6 全部 42 个槽位一并写清楚
     * （不写等于保留设备的旧值，会出现「界面上删掉的时段还在生效」）。
     *
     * @param ranges 所有时段，每段自带星期；同一星期最多 {@link #DAY_SECTIONS} 段
     */
    public void saveAutoMovement(String deviceId, String channelCode, int index, boolean enable,
                                 String function, Integer presetId, Integer tourId, Integer patternId,
                                 Integer scanId, Integer homingSeconds,
                                 List<DayRange> ranges) {
        if (index < 0 || index > 3) {
            throw BizException.badRequest("定时任务下标必须是 0~3：" + index);
        }
        String fn = normalizeFunction(function);
        int ch = cfgChannel(deviceId, channelCode);
        String prefix = "PtzAutoMovement[" + ch + "][" + index + "]";

        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(prefix + ".Enable", enable ? "true" : "false");
        kv.put(prefix + ".Function", fn);
        if (presetId != null) {
            kv.put(prefix + ".PresetId", String.valueOf(Math.max(0, presetId)));
        }
        if (tourId != null) {
            kv.put(prefix + ".TourId", String.valueOf(Math.max(0, tourId)));
        }
        if (patternId != null) {
            kv.put(prefix + ".PatternId", String.valueOf(Math.max(0, patternId)));
        }
        if (scanId != null) {
            kv.put(prefix + ".ScanId", String.valueOf(Math.max(0, scanId)));
        }
        if (homingSeconds != null) {
            if (homingSeconds < HOMING_MIN || homingSeconds > HOMING_MAX) {
                throw BizException.badRequest("自动返回原始位置只能在 " + HOMING_MIN + "~"
                        + HOMING_MAX + " 秒之间：" + homingSeconds);
            }
            kv.put(prefix + ".AutoHoming.Time", String.valueOf(homingSeconds));
        }

        if (ranges != null) {
            // 按天归集，同时校验「每天最多 6 段」
            List<List<String>> byDay = new ArrayList<>(7);
            for (int d = 0; d < 7; d++) {
                byDay.add(new ArrayList<>(DAY_SECTIONS));
            }
            for (DayRange r : ranges) {
                if (r == null) {
                    continue;
                }
                if (r.day() < 0 || r.day() > 6) {
                    throw BizException.badRequest("星期下标必须是 0~6：" + r.day());
                }
                String from = normalizeTime(r.from());
                String to = normalizeTime(r.to());
                if (from.compareTo(to) >= 0) {
                    throw BizException.badRequest("结束时间必须晚于开始时间：" + from + "-" + to);
                }
                List<String> slots = byDay.get(r.day());
                if (slots.size() >= DAY_SECTIONS) {
                    throw BizException.badRequest("每天最多 " + DAY_SECTIONS + " 个时段，星期 "
                            + r.day() + " 已超出");
                }
                slots.add(from + "-" + to);
            }
            // 全部 42 个槽位都写：没传的置成「禁用」，否则设备上删掉的时段仍然生效
            for (int d = 0; d < 7; d++) {
                List<String> slots = byDay.get(d);
                for (int s = 0; s < DAY_SECTIONS; s++) {
                    String value = s < slots.size()
                            ? "1 " + slots.get(s)
                            : "0 " + EMPTY_SECTION;   // 占位用全天，与出厂默认一致
                    kv.put(prefix + ".TimeSection[" + d + "][" + s + "]", value);
                }
            }
        }
        write(deviceId, kv);
        log.info("定时任务已保存: device={} channel={} index={} enable={} function={} homing={}s 时段={}",
                deviceId, channelCode, index, enable, fn, homingSeconds,
                ranges == null ? "不改" : ranges.size() + " 段");
    }

    // ==================== 写：线扫 / 限位 ====================

    /**
     * 保存线扫参数（速度）。
     *
     * <p>左右边界不在这里写：边界必须由「以当前位置为边界」的即时动作产生
     * （RPC2 {@code ptz.setScanLimit}），手工填坐标没有意义。
     *
     * @param scanId 1-based 线扫编号
     */
    public void saveScan(String deviceId, String channelCode, int scanId, int speed) {
        if (scanId < 1) {
            throw BizException.badRequest("线扫编号必须从 1 开始：" + scanId);
        }
        if (speed < SCAN_SPEED_MIN || speed > SCAN_SPEED_MAX) {
            throw BizException.badRequest("线扫速度只能在 " + SCAN_SPEED_MIN + "~"
                    + SCAN_SPEED_MAX + " 之间：" + speed);
        }
        int ch = cfgChannel(deviceId, channelCode);
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("AutoScan[" + ch + "][" + (scanId - 1) + "].ScanSpeed", String.valueOf(speed));
        write(deviceId, kv);
        log.info("线扫参数已保存: device={} channel={} scanId={} speed={}",
                deviceId, channelCode, scanId, speed);
    }

    /**
     * 保存一条巡迹的名称（设备 {@code AutoPattern[ch][idx].Name}）。
     *
     * <p>只写名称，<b>不写 Enable</b>：设备上的 {@code Enable} 表示「这条巡迹录过内容」，
     * 由设备在录制结束时自己置位，平台改它没有意义（改成 true 也不会让空巡迹能跑）。
     * 与巡航组的 {@code Enable} 同理 —— 那是设备侧事实，不是配置项。
     *
     * @param patternId 1-based 巡迹编号
     * @param name      新名称，为空时保留设备原值
     */
    public void savePatternName(String deviceId, String channelCode, int patternId, String name) {
        if (patternId < 1) {
            throw BizException.badRequest("巡迹编号必须从 1 开始：" + patternId);
        }
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("巡迹名称不能为空");
        }
        if (name.length() > PATTERN_NAME_MAX) {
            throw BizException.badRequest("巡迹名称最多 " + PATTERN_NAME_MAX + " 个字符：" + name.length());
        }
        int ch = cfgChannel(deviceId, channelCode);
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("AutoPattern[" + ch + "][" + (patternId - 1) + "].Name", name.trim());
        write(deviceId, kv);
        log.info("巡迹名称已保存: device={} channel={} patternId={} name={}",
                deviceId, channelCode, patternId, name);
    }

    /**
     * 一次保存巡迹的「名称 + 停留时间」。
     *
     * <p>两者在设备上是<b>两个不同的配置项</b>：
     * 名称在 {@code AutoPattern}（CGI 可写），停留时间在 {@code PatternPlayDetail}
     * （CGI 返回 400，只能走 RPC2）。分开写是因为通路不同，
     * 但对用户来说就是「保存这条巡迹」一个动作。
     *
     * @param name        新名称，{@code null} 表示不改
     * @param staySeconds 停留秒数，{@code null} 表示不改
     */
    public void savePattern(String deviceId, String channelCode, int patternId,
                            String name, Integer staySeconds) {
        if (name != null && !name.isBlank()) {
            savePatternName(deviceId, channelCode, patternId, name);
        }
        if (staySeconds != null) {
            savePatternStay(deviceId, channelCode, patternId, staySeconds);
        }
    }

    /**
     * 保存巡迹停留时间（设备「巡跡」页的「停留時間(S)」）。
     *
     * <p>写 {@code PatternPlayDetail[ch][idx].StartPatternPlayDelay}，<b>值是毫秒</b>：
     * 界面填 15 秒 → 发 15000。
     *
     * <p><b>为什么走 RPC2 而不是 CGI</b>：实测
     * {@code configManager.cgi?action=setConfig&name=PatternPlayDetail[0][0].StartPatternPlayDelay}
     * 一律 <b>HTTP 400</b>（值不变），设备自己的 Web UI 改同一字段却成功 ——
     * 它走的是 {@code POST /RPC2} 的 {@code configManager.setConfig}，且一次提交整个
     * 32 条的 table 数组。所以这里先读全量、改一个元素、原样写回。
     *
     * @param patternId   1-based 巡迹编号
     * @param staySeconds 停留秒数，0~3600
     */
    public void savePatternStay(String deviceId, String channelCode, int patternId, int staySeconds) {
        if (patternId < 1) {
            throw BizException.badRequest("巡迹编号必须从 1 开始：" + patternId);
        }
        if (staySeconds < PATTERN_STAY_MIN || staySeconds > PATTERN_STAY_MAX) {
            throw BizException.badRequest("巡迹停留时间只能在 " + PATTERN_STAY_MIN + "~"
                    + PATTERN_STAY_MAX + " 秒之间：" + staySeconds);
        }
        var session = capability.requireSession(deviceId);
        int httpPort = capability.httpPort();
        int ch = cfgChannel(deviceId, channelCode);
        try {
            /*
             * 读回原结构再改。PatternPlayDetail 是 [通道][下标] 两层数组，
             * 元素只有 StartPatternPlayDelay 一个键。直接改设备给的对象，
             * 层级和字段名都不会错（手写 table 曾把 PtzTour 写成 [null] 整坏过）。
             */
            Object raw = capability.rpc().getConfigItem(session, httpPort, "PatternPlayDetail");
            if (!(raw instanceof List<?> channels) || channels.size() <= ch) {
                throw BizException.badRequest("设备没有返回 PatternPlayDetail 配置，无法保存停留时间");
            }
            Object chanObj = channels.get(ch);
            if (!(chanObj instanceof List<?> items) || items.size() < patternId) {
                throw BizException.badRequest("PatternPlayDetail 通道 " + ch + " 只有 "
                        + (chanObj instanceof List<?> l ? l.size() : 0) + " 条，没有第 " + patternId + " 条");
            }
            List<Object> newChan = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                Object it = items.get(i);
                if (i == patternId - 1) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    if (it instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    }
                    // 秒 → 毫秒
                    row.put("StartPatternPlayDelay", staySeconds * 1000);
                    newChan.add(row);
                } else {
                    newChan.add(it);
                }
            }
            List<Object> newTable = new ArrayList<>(channels.size());
            for (int c = 0; c < channels.size(); c++) {
                newTable.add(c == ch ? newChan : channels.get(c));
            }
            capability.rpc().setConfigItem(session, httpPort, "PatternPlayDetail", newTable);
            log.info("巡迹停留时间已保存: device={} channel={} patternId={} staySeconds={}",
                    deviceId, channelCode, patternId, staySeconds);
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw capability.translate("保存巡迹停留时间", e);
        }
    }

    /** 保存旋转限制开关（上下限位本身是即时动作，不在这里设）。 */
    public void saveLimit(String deviceId, String channelCode, LimitCfg cfg) {
        if (cfg == null) {
            throw BizException.badRequest("缺少限位配置");
        }
        int ch = cfgChannel(deviceId, channelCode);
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("PtzLimit[" + ch + "].Enable", cfg.enable() ? "true" : "false");
        kv.put("PtzLimit[" + ch + "].HorizontalLimitEnable",
                cfg.horizontalEnable() ? "true" : "false");
        write(deviceId, kv);
        log.info("旋转限制已保存: device={} channel={} enable={} horizontal={}",
                deviceId, channelCode, cfg.enable(), cfg.horizontalEnable());
    }
    /** 定时任务规则数量（能力位 TaskNum=4）。 */
    public static int autoMovementCount() {
        return 4;
    }

    /** 每条规则每周的时间段数量（设备 TimeSection[d][0..5]）。 */
    public static int daySectionCount() {
        return DAY_SECTIONS;
    }

    // ==================== 解析：巡航组 ====================

    private List<Tour> parseTours(Map<String, String> raw, int ch) {
        Map<Integer, Map<Integer, int[]>> points = new TreeMap<>();
        Map<Integer, String> names = new TreeMap<>();
        Map<Integer, Boolean> enables = new TreeMap<>();

        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            Matcher m = TOUR_FIELD.matcher(key);
            if (m.matches() && Integer.parseInt(m.group(1)) == ch) {
                int route = Integer.parseInt(m.group(2));
                switch (m.group(3)) {
                    case "Name" -> names.put(route, e.getValue());
                    case "Enable" -> enables.put(route, Boolean.parseBoolean(e.getValue()));
                    default -> { }
                }
                continue;
            }
            Matcher p = TOUR_POINT.matcher(key);
            if (p.matches() && Integer.parseInt(p.group(1)) == ch) {
                int route = Integer.parseInt(p.group(2));
                int slot = Integer.parseInt(p.group(3));
                int field = Integer.parseInt(p.group(4));
                if (field < 0 || field > 2) {
                    continue;
                }
                int[] pt = points.computeIfAbsent(route, k -> new TreeMap<>())
                        .computeIfAbsent(slot, k -> new int[]{-1, 15, 7});
                try {
                    pt[field] = Integer.parseInt(e.getValue().trim());
                } catch (NumberFormatException ignored) {
                    // 非法值按默认处理，不让一条坏数据毁掉整个列表
                }
            }
        }

        List<Tour> tours = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, int[]>> e : points.entrySet()) {
            int route = e.getKey();
            List<TourPoint> pts = new ArrayList<>();
            for (int[] pt : e.getValue().values()) {
                if (pt[0] >= 0) {
                    // 设备存 0-based，对外转回 1-based
                    pts.add(new TourPoint(pt[0] + 1, pt[1], pt[2]));
                }
            }
            // 空线也返回：UI 需要知道「这条线号可用但还没配点」
            tours.add(new Tour(route + 1, names.getOrDefault(route, "巡航群組" + (route + 1)),
                    enables.getOrDefault(route, false), pts));
        }
        return tours;
    }

    // ==================== 解析：线扫 ====================

    private List<Scan> parseScans(Map<String, String> raw, int ch) {
        Map<Integer, Map<String, String>> byIdx = splitIndexed(raw, "AutoScan", ch);
        List<Scan> list = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> e : byIdx.entrySet()) {
            Map<String, String> f = e.getValue();
            list.add(new Scan(e.getKey() + 1,
                    Boolean.parseBoolean(f.getOrDefault("LeftEnable", "false")),
                    Boolean.parseBoolean(f.getOrDefault("RightEnable", "false")),
                    intOf(f.get("ScanSpeed"), 4),
                    f.getOrDefault("ScanDirection", "Negative"),
                    positionOf(f, "LeftPosition"),
                    positionOf(f, "RightPosition")));
        }
        return list;
    }

    /** 把 {@code LeftPosition[0..2]} 拼成可读字符串；未设置的边界返回空串。 */
    private static String positionOf(Map<String, String> f, String base) {
        String x = f.get(base + "[0]");
        String y = f.get(base + "[1]");
        String z = f.get(base + "[2]");
        if (x == null || "null".equalsIgnoreCase(x.trim())) {
            return "";
        }
        return x.trim() + ", " + (y == null ? "-" : y.trim()) + ", " + (z == null ? "-" : z.trim());
    }

    // ==================== 解析：巡迹 ====================

    /**
     * 解析巡迹列表。
     *
     * <p>名称/启用来自 {@code AutoPattern}，停留时间来自 {@code PatternPlayDetail}
     * （两个配置项按同一个下标对应）。缺任一项不影响另一项显示。
     */
    private List<PatternCfg> parsePatterns(Map<String, String> patternRaw,
                                           Map<String, String> detailRaw, int ch) {
        Map<Integer, Map<String, String>> byIdx = splitIndexed(patternRaw, "AutoPattern", ch);
        Map<Integer, Map<String, String>> detail = splitIndexed(detailRaw, "PatternPlayDetail", ch);
        List<PatternCfg> list = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> e : byIdx.entrySet()) {
            Map<String, String> f = e.getValue();
            int idx = e.getKey();
            // 设备存毫秒，对外一律给秒
            int delayMs = intOf(detail.getOrDefault(idx, Map.of())
                    .get("StartPatternPlayDelay"), 0);
            int staySeconds = delayMs > 0 ? delayMs / 1000 : 0;
            list.add(new PatternCfg(idx + 1,
                    f.getOrDefault("Name", "Pattern" + (idx + 1)),
                    Boolean.parseBoolean(f.getOrDefault("Enable", "false")),
                    staySeconds));
        }
        return list;
    }

    /**
     * 把 {@code Name[ch][idx].Field} 形式的扁平键拆成 {@code idx → {Field → 值}}。
     * 用于 AutoScan / AutoPattern 这类「一维数组 + 扁平字段」的配置。
     */
    private static Map<Integer, Map<String, String>> splitIndexed(
            Map<String, String> raw, String name, int ch) {
        String head = name + "[" + ch + "][";
        Map<Integer, Map<String, String>> byIdx = new TreeMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(head)) {
                continue;
            }
            int close = key.indexOf(']', head.length());
            if (close < 0) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(key.substring(head.length(), close));
            } catch (NumberFormatException ex) {
                continue;
            }
            String rest = key.substring(close + 1);
            if (rest.startsWith(".")) {
                rest = rest.substring(1);
            }
            byIdx.computeIfAbsent(idx, k -> new LinkedHashMap<>()).put(rest, e.getValue());
        }
        return byIdx;
    }

    // ==================== 解析：速度 / 空闲 / 定时 / 限位 / 运行 ====================

    private SpeedCfg parseSpeed(Map<String, String> raw, int ch) {
        int ptz = intOf(raw.get("PtzSpeed[" + ch + "].PtzSpeed"), 1);
        int pattern = intOf(raw.get("PtzSpeed[" + ch + "].PatternSpeed"), 5);
        return new SpeedCfg(ptz, pattern);
    }

    /**
     * 解析水平旋转速度（{@code AutoPan[ch].PanSpeed}）。
     *
     * <p>设备内部存 0~7，对外统一用界面口径 1~8（+1）。
     * 键形如 {@code AutoPan[0].PanSpeed=2}。缺值时给界面下限 1。
     */
    private AutoPanCfg parseAutoPan(Map<String, String> raw, int ch) {
        int internal = intOf(raw.get("AutoPan[" + ch + "].PanSpeed"), 0);
        return new AutoPanCfg(internal + 1);
    }

    private IdleMotionCfg parseIdle(Map<String, String> raw, int ch) {
        String p = "IdleMotion[" + ch + "].";
        return new IdleMotionCfg(
                Boolean.parseBoolean(raw.getOrDefault(p + "Enable", "false")),
                raw.getOrDefault(p + "Function", "None"),
                intOf(raw.get(p + "PresetId"), 1),
                intOf(raw.get(p + "TourId"), 1),
                intOf(raw.get(p + "PatternId"), 1),
                intOf(raw.get(p + "ScanId"), 1),
                intOf(raw.get(p + "Timer"), 10));
    }

    /**
     * 解析定时任务（{@code PtzAutoMovement}）。
     *
     * <p>时段结构是 {@code TimeSection[7][6]} —— 7 天 × 每天最多 6 段，
     * 每段首位是<b>自己的</b>启用标记。这里把全部 42 个槽位透出成
     * {@code "d[slot]|<值>"}，由前端按启用标记过滤。
     *
     * <p>⚠️ 早期只收 {@code [d][0]} 那一格，其余 5 格直接丢，于是
     * 「同一天上午 + 下午两段」读回来只剩一段，界面看着像保存失败。
     */
    private List<AutoMovement> parseAuto(Map<String, String> raw, int ch) {
        Map<Integer, Map<String, String>> byIndex = new TreeMap<>();
        Map<Integer, TreeMap<Integer, TreeMap<Integer, String>>> sections = new TreeMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            Matcher m = AUTO_FIELD.matcher(e.getKey());
            if (m.matches() && Integer.parseInt(m.group(1)) == ch) {
                byIndex.computeIfAbsent(Integer.parseInt(m.group(2)), k -> new LinkedHashMap<>())
                        .put(m.group(3), e.getValue());
                continue;
            }
            Matcher s = AUTO_SECTION.matcher(e.getKey());
            if (s.matches() && Integer.parseInt(s.group(1)) == ch) {
                sections.computeIfAbsent(Integer.parseInt(s.group(2)), k -> new TreeMap<>())
                        .computeIfAbsent(Integer.parseInt(s.group(3)), k -> new TreeMap<>())
                        .put(Integer.parseInt(s.group(4)), normalizeSection(e.getValue()));
            }
        }
        List<AutoMovement> list = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> e : byIndex.entrySet()) {
            Map<String, String> f = e.getValue();
            // TreeMap 两层已按「天 → 段号」有序，直接展开即可
            List<String> secs = new ArrayList<>();
            for (Map.Entry<Integer, TreeMap<Integer, String>> day
                    : sections.getOrDefault(e.getKey(), new TreeMap<>()).entrySet()) {
                for (Map.Entry<Integer, String> slot : day.getValue().entrySet()) {
                    secs.add(day.getKey() + "[" + slot.getKey() + "]|" + slot.getValue());
                }
            }
            list.add(new AutoMovement(e.getKey(),
                    Boolean.parseBoolean(f.getOrDefault("Enable", "false")),
                    f.getOrDefault("Function", "None"),
                    intOf(f.get("PresetId"), 0),
                    intOf(f.get("TourId"), 0),
                    intOf(f.get("PatternId"), 0),
                    intOf(f.get("ScanId"), 0),
                    intOf(f.get("AutoHoming.Time"), 0),
                    secs));
        }
        return list;
    }

    private LimitCfg parseLimit(Map<String, String> raw, int ch) {
        String p = "PtzLimit[" + ch + "].";
        return new LimitCfg(
                Boolean.parseBoolean(raw.getOrDefault(p + "Enable", "false")),
                Boolean.parseBoolean(raw.getOrDefault(p + "HorizontalLimitEnable", "false")),
                boundaryOf(raw.get(p + "UpLimit.Boundary")),
                boundaryOf(raw.get(p + "DownLimit.Boundary")));
    }

    /** 边界值：设备未下发时返回「未设置」而不是空串，UI 直接显示。 */
    private static String boundaryOf(String v) {
        if (v == null || v.isBlank()) {
            return "未设置";
        }
        return v.trim();
    }

    /**
     * 解析当前运行状态（{@code PtzMovement}）。
     *
     * <h3>只有这 4 种 Function 算「在运行」</h3>
     * {@code Tour} / {@code Pattern} / {@code Scan} / {@code AutoPan}，
     * 且 {@code TaskName != Unknown}（实测运行中是 {@code Manual}）。
     *
     * <p><b>{@code Function=Preset} 必须排除</b>：那是「云台正转向某个预置位 /
     * 停在预置位上」的残留态。实测踩过的坑：
     * 巡航跑完后设备把 Function 留在 {@code Preset/Index=1/Manual} 上并<b>长期不变</b>，
     * 而设备自己的「執行」开关此时是<b>关</b>的。把它当「在运行」会让整页控件永久禁用。
     *
     * <p>巡航运行中的采样是稳定的 {@code Tour/0/Manual}（0.4s 间隔连采 25 次无抖动），
     * 所以这个判据可用。
     */
    private RunningCfg parseRunning(Map<String, String> raw, int ch) {
        String p = "PtzMovement[" + ch + "].";
        String function = raw.getOrDefault(p + "Function", "None").trim();
        int index = intOf(raw.get(p + "Index"), 0);
        String taskName = raw.getOrDefault(p + "TaskName", "Unknown").trim();
        boolean busy = !"Unknown".equalsIgnoreCase(taskName);
        boolean running = busy && switch (function.toLowerCase()) {
            case "tour", "pattern", "scan", "autopan" -> true;
            default -> false;
        };
        return new RunningCfg(running, function, index, taskName);
    }

    // ==================== 内部 ====================

    private Map<String, String> readOrEmpty(String deviceId, String name) {
        try {
            return capability.cgi().read(capability.requireSession(deviceId), name, capability.httpPort());
        } catch (Exception e) {
            // 单项不支持不该让整页打不开：UI 那一段显示为空即可，其余照常
            log.warn("读取云台配置项 {} 失败(device={}): {}", name, deviceId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 写配置项（自动分批）。
     *
     * <p><b>按「编码后长度」分批，不是按个数。</b>设备是 GET + query string，
     * URL 超长直接回 HTTP 501。早期按「30 个键一批」是拿 {@code PtzTour}
     * （键约 50 字符）试出来的；换成 {@code PtzAutoMovement.TimeSection}
     * 就不够用了 —— 键本身约 62 字符，值里还有空格和冒号，
     * 编码后 {@code 1 08:00:00-12:00:00} 变 {@code 1%2008%3A00%3A00-12%3A00%3A00}（33 字符），
     * 一对就 ~96 字符，30 对 ≈ 2900 字符 → 501。
     *
     * <p>按时长兜住后，一条定时任务 7×6 共 42 个时段键也能正常写入。
     */
    private void write(String deviceId, Map<String, String> kv) {
        try {
            /*
             * 每批的「原始 key=value」长度上限。取 900 是因为：
             * URL 编码后中文字符 3 字节变 9 个字符、空格变 3 个字符，
             * 最坏情况约 ×3 → 2700，仍在设备接受的范围内（实测 2900 会 501）。
             * 再叠加 GET 前缀（路径 + action/name 参数 ≈ 120 字符）也够安全。
             */
            final int budget = 900;
            var session = capability.requireSession(deviceId);
            Map<String, String> buf = new LinkedHashMap<>();
            int used = 0;
            for (Map.Entry<String, String> e : kv.entrySet()) {
                int len = e.getKey().length() + 1 + (e.getValue() == null ? 0 : e.getValue().length());
                // 单条就超预算时也要强制发出去，否则会卡在 buf 里永远不发
                if (!buf.isEmpty() && used + len > budget) {
                    capability.cgi().write(session, buf, capability.httpPort());
                    buf.clear();
                    used = 0;
                }
                buf.put(e.getKey(), e.getValue());
                used += len;
            }
            if (!buf.isEmpty()) {
                capability.cgi().write(session, buf, capability.httpPort());
            }
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            throw capability.translate("写入云台配置", e);
        }
    }

    /** 配置项里的通道下标：与 SDK 通道号一致（IPC 0 / NVR 1~N）。 */
    private int cfgChannel(String deviceId, String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return 0;
        }
        return capability.sdkChannel(capability.requireSession(deviceId), deviceId, channelCode);
    }

    private static String normalizeFunction(String function) {
        if (function == null || function.isBlank()) {
            return "None";
        }
        return switch (function.trim().toLowerCase()) {
            case "preset" -> "Preset";
            case "tour" -> "Tour";
            case "pattern" -> "Pattern";
            case "scan" -> "Scan";
            default -> "None";
        };
    }

    private static String normalizeTime(String t) {
        String s = t == null ? "" : t.trim();
        if (s.matches("\\d{2}:\\d{2}:\\d{2}")) {
            return s;
        }
        if (s.matches("\\d{2}:\\d{2}")) {
            return s + ":00";
        }
        throw BizException.badRequest("时间格式应为 HH:mm 或 HH:mm:ss：" + t);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 归一化 {@code TimeSection} 的原文。
     *
     * <p><b>设备回读的分隔符是 {@code +} 而不是空格</b>：
     * 写入 {@code "1 09:30:00-17:45:00"}，设备回读 {@code "1+09:30:00-17:45:00"}。
     * 这是设备的真实存储形态（出厂默认值同样是 {@code "0+08:00:00-18:00:00"}），
     * 不是编码问题 —— 我们的 CGI 写入已经把 {@code +} 换成 {@code %20}。
     *
     * <p>回读不做这一步的话，前端用 {@code startsWith("1 ")} 判断启用、
     * 用空格分隔的正则解析时间段，两个都会失配，表现为「定时任务保存成功但界面上星期全空、
     * 时段回到默认值」。统一在服务端还原成空格，前端只认一种格式。
     */
    static String normalizeSection(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return s;
        }
        // 首位是启用标记，紧接一个分隔符（空格或 +），后面是 HH:mm:ss-HH:mm:ss
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '0' || s.charAt(i) == '1')) {
            i++;
        }
        if (i == 0 || i >= s.length()) {
            return s.replace('+', ' ').trim();
        }
        return s.charAt(0) + " " + s.substring(i).replaceFirst("^[+\\s]+", "");
    }

    private static int intOf(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
