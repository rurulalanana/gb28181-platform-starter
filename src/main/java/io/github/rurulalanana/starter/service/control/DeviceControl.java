package io.github.rurulalanana.starter.service.control;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.vo.ControlResultVo;

import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * 设备控制能力的<b>统一契约</b>：一套方法签名，每个协议给一份实现。
 *
 * <h3>要解决的问题</h3>
 * 改造前 {@code ControlService} 的 32 个公开方法里，{@code providerOf(...) != null}
 * 出现了 31 次，形状清一色是
 * <pre>
 *   Target t = target(channelId);
 *   if (providerOf(t.sipTarget()) != null) {   // 非 null = 厂商 SDK，null = 国标
 *       dahuaCapability.reboot(t.sipTarget());
 *       return;
 *   }
 *   sendControl(channelId, id -&gt; ManscdpBuilder.teleBoot(...));
 * </pre>
 * 四个具体问题：
 * <ol>
 *   <li><b>用 {@code null} 承载语义</b>——每个调用点都得记住「null 表示国标」这个暗号；</li>
 *   <li><b>同方法内重复调用</b>——{@code forceIFrame} / {@code formatSdCard} 各调两次，
 *       每次都带 1~2 次数据库查询；</li>
 *   <li><b>潜在 NPE</b>——{@code if (providerOf(x) != null) { ... providerOf(x).kind() ... }}
 *       两次之间设备记录消失就崩；</li>
 *   <li><b>「不支持」的判断与文案散落</b>——判据在 if 里，文案在字符串里，
 *       前端拿不到能力表。</li>
 * </ol>
 *
 * <h3>本接口的形状</h3>
 * <b>每个方法都有默认实现，默认就抛「不支持」</b>。实现类只覆写它真正做得到的方法：
 * <ul>
 *   <li>{@link Gb28181DeviceControl}——国标是主链路，32 个全部覆写；</li>
 *   <li>{@link DahuaDeviceControl}——只覆写大华 SDK 做得到的，其余继承默认实现，
 *       天生满足「不支持的实现就直接返回异常」。</li>
 * </ul>
 * 新增一个厂商时不需要改任何调用点，也不需要碰国标实现。
 *
 * <h3>与 {@code DeviceAccessProvider} 的分工</h3>
 * 两者都叫「契约」，但职责不重叠：
 * <table border="1">
 *   <tr><th>契约</th><th>管什么</th><th>国标是否实现</th></tr>
 *   <tr><td>{@code DeviceAccessProvider}</td>
 *       <td>国标做不到的<b>接入</b>能力：探测、目录、拉流地址</td>
 *       <td>不实现（国标走平台原生 SIP 链路）</td></tr>
 *   <tr><td>本接口</td>
 *       <td><b>下发控制命令</b>：双方都有的语义</td>
 *       <td>主实现，厂商只做覆写</td></tr>
 * </table>
 *
 * <h3>边界：什么不该塞进来</h3>
 * 厂商私有、国标没有对应语义的能力<b>不进本接口</b>——大华的
 * {@code cameraconfig}（CGI/RPC2 图像参数）、{@code ptz/tour}（巡航组配置项）、
 * {@code register/*}（主动注册表）、{@code record/download}（私有下载协议）
 * 继续留在 {@code /api/v1/dahua/**}。
 *
 * <p>把它们硬塞进来只会造出 20 个「必然抛不支持」的方法，
 * 那是把 {@code if} 分支搬进接口，不是消除它。统一的是「双方都有的语义」，
 * 不是「所有端点」。
 */
public interface DeviceControl {

    // ==================== 元信息 ====================

    /** 本实现对应的接入方式。 */
    DeviceKind kind();

    /**
     * 本实现支持的能力集合。
     *
     * <p>既供 {@link #supports} 判断，也供
     * {@code GET /api/v1/device/{id}/capabilities} 直接下发给前端。
     */
    EnumSet<DeviceCapability> capabilities();

    /** 是否支持某项能力。 */
    default boolean supports(DeviceCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * 某项能力做不到的<b>具体原因</b>（面向用户，可为空）。
     *
     * <p>由实现类覆盖：同一个能力在不同厂商那边缺失的理由不一样
     * （大华没有强制关键帧接口，海康可能是别的原因）。
     * 返回空串表示「没有额外说明」，异常里只报能力名。
     */
    default String unsupportedHint(DeviceCapability capability) {
        return "";
    }

    /**
     * 构造「不支持」异常。
     *
     * <p>文案在这里统一产出，实现类只负责给 {@link #unsupportedHint}。
     * 改造前 32 个方法各写一套字符串，同一个能力从不同入口点进去说的话都不一样。
     */
    default BizException unsupported(DeviceCapability capability) {
        String hint = unsupportedHint(capability);
        return BizException.badRequest("该设备通过 " + kind().code() + " 接入，不支持「"
                + capability.label() + "」" + (hint == null || hint.isBlank() ? "" : "；" + hint));
    }

    // ==================== 云台 ====================

    /** 云台方向控制。{@code speed} 0~255，默认 129。 */
    default void ptz(Ptz cmd) {
        throw unsupported(DeviceCapability.PTZ);
    }

    /** 变倍 / 聚焦 / 光圈。 */
    default void focusIris(FocusIris cmd) {
        throw unsupported(DeviceCapability.FOCUS_IRIS);
    }

    /** 按绝对角度定位（2022 版）。 */
    default void ptzPrecise(PtzPrecise cmd) {
        throw unsupported(DeviceCapability.PTZ_PRECISE);
    }

    /** 框选区域放大 / 缩小。 */
    default ControlResultVo dragZoom(DragZoom cmd) {
        throw unsupported(DeviceCapability.DRAG_ZOOM);
    }

    /** 预置位设置 / 调用 / 删除。 */
    default void preset(Preset cmd) {
        throw unsupported(DeviceCapability.PRESET);
    }

    /**
     * 触发一次预置位清单查询。
     *
     * <p>国标是异步的（下发查询 → 等 MANSCDP 应答 → 落快照）；
     * 厂商 SDK 多为同步读，实现类可以覆写成空操作（数据由查询接口直接返回）。
     */
    default void queryPresets(String channelId) {
        throw unsupported(DeviceCapability.PRESET_QUERY);
    }

    /** 巡航组：增删点、设速度/停留、启停、查询。 */
    default void cruise(Cruise cmd) {
        throw unsupported(DeviceCapability.CRUISE);
    }

    /** 查询巡航轨迹。 */
    default void queryCruiseTrack(String channelId, Integer number) {
        throw unsupported(DeviceCapability.CRUISE_QUERY);
    }

    /** 查询看守位。 */
    default void queryHomePosition(String channelId) {
        throw unsupported(DeviceCapability.HOME_POSITION);
    }

    /** 设置看守位。 */
    default void homePosition(HomePosition cmd) {
        throw unsupported(DeviceCapability.HOME_POSITION);
    }

    /** 雨刷开关。 */
    default void wiper(String channelId, String command) {
        throw unsupported(DeviceCapability.AUX_SWITCH);
    }

    /** 补光灯开关。 */
    default void fillLight(String channelId, String command, Integer index) {
        throw unsupported(DeviceCapability.AUX_SWITCH);
    }

    // ==================== 设备控制 ====================

    /** 设备端录像启停。 */
    default void recording(Recording cmd) {
        throw unsupported(DeviceCapability.RECORDING);
    }

    /** 布防 / 撤防。 */
    default void guard(Arming cmd) {
        throw unsupported(DeviceCapability.GUARD);
    }

    /** 远程重启。 */
    default void reboot(String channelId) {
        throw unsupported(DeviceCapability.REBOOT);
    }

    /** 强制 I 帧。 */
    default void forceIFrame(String channelId) {
        throw unsupported(DeviceCapability.FORCE_I_FRAME);
    }

    /** 存储卡格式化。{@code sdCardId = 0} 表示全部。 */
    default void formatSdCard(String channelId, Integer sdCardId) {
        throw unsupported(DeviceCapability.FORMAT_SD_CARD);
    }

    /** 报警复位。 */
    default void alarmReset(String channelId) {
        throw unsupported(DeviceCapability.ALARM_RESET);
    }

    /** 设备校时。{@code time} 为 {@code null} 表示用平台当前时间。 */
    default void syncTime(SyncTime cmd) {
        throw unsupported(DeviceCapability.SYNC_TIME);
    }

    /** 透传 XML 配置（2022 版 DeviceConfig）。 */
    default void deviceConfig(String channelId, String xml) {
        throw unsupported(DeviceCapability.DEVICE_CONFIG);
    }

    /** 事件订阅（目录 / 报警 / 位置 / PTZ）。 */
    default void subscribe(Subscribe cmd) {
        throw unsupported(DeviceCapability.SUBSCRIBE);
    }

    // ==================== 查询 ====================

    /** 设备运行状态查询。 */
    default void queryDeviceStatus(String channelId) {
        throw unsupported(DeviceCapability.QUERY_STATUS);
    }

    /** 设备信息查询。 */
    default void queryDeviceInfo(String channelId) {
        throw unsupported(DeviceCapability.QUERY_INFO);
    }

    /** 配置项查询（A.2.4.7）。 */
    default void queryConfig(String channelId, String configType) {
        throw unsupported(DeviceCapability.QUERY_CONFIG);
    }

    /** 存储卡状态查询。 */
    default void querySdCardStatus(String channelId) {
        throw unsupported(DeviceCapability.QUERY_SD_CARD);
    }

    /** 云台角度查询。 */
    default void queryPtzPosition(String channelId) {
        throw unsupported(DeviceCapability.QUERY_PTZ_POSITION);
    }

    // ==================== 配置 / 升级 ====================

    /** OSD 叠加配置。 */
    default void osdConfig(Osd cmd) {
        throw unsupported(DeviceCapability.OSD_CONFIG);
    }

    /** 抓拍参数配置。 */
    default void snapshotConfig(Snapshot cmd) {
        throw unsupported(DeviceCapability.SNAPSHOT_CONFIG);
    }

    /** 设备软件升级。最终结果由设备另行通知。 */
    default ControlResultVo deviceUpgrade(Upgrade cmd) {
        throw unsupported(DeviceCapability.DEVICE_UPGRADE);
    }

    /** 目标跟踪：{@code Auto} / {@code Manual} / {@code Stop}。 */
    default ControlResultVo targetTrack(TargetTrack cmd) {
        throw unsupported(DeviceCapability.TARGET_TRACK);
    }

    // ==================== 报警 / 喊话 ====================

    /** 平台向设备下发报警通知（A.2.5.9）。 */
    default void alarmNotify(AlarmNotify cmd) {
        throw unsupported(DeviceCapability.ALARM_NOTIFY);
    }

    /** 语音喊话（A.2.3.1.11 Broadcast）。 */
    default void talk(String channelId, String sourceId) {
        throw unsupported(DeviceCapability.TALK);
    }

    // ==================== 参数对象 ====================
    /*
     * 为什么用 record 而不是照搬长参数列表：dragZoom 8 个、targetTrack 9 个、
     * osdConfig 10 个、cruise 6 个。默认实现只需要「忽略参数并抛异常」，
     * 长参数列表会让 32 个 default 方法变成一堵墙，无法一眼扫出有哪些能力。
     */

    /** 云台方向控制。 */
    record Ptz(String channelId, String command, Integer speed) {
    }

    /** 变倍 / 聚焦 / 光圈。 */
    record FocusIris(String channelId, String command, Integer speed) {
    }

    /** 精确云台定位：角度 + 变焦倍数。 */
    record PtzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
    }

    /** 拉框缩放：坐标均为<b>像素值</b>，{@code length}/{@code width} 是播放窗口尺寸。 */
    record DragZoom(String channelId, boolean zoomIn, int length, int width,
                    int midpointX, int midpointY, int lengthX, int lengthY) {
    }

    /** 预置位：{@code set} / {@code goto} / {@code remove}。 */
    record Preset(String channelId, String command, Integer preset) {
    }

    /** 巡航组控制。 */
    record Cruise(String channelId, String command, Integer groupId, Integer preset,
                  Integer speed, Integer stay, String name) {
    }

    /** 看守位。 */
    record HomePosition(String channelId, Boolean enabled, Integer resetTime,
                        Integer presetIndex) {
    }

    /** 设备端录像启停。 */
    record Recording(String channelId, String command, Integer streamNumber) {
    }

    /** 布防 / 撤防。 */
    record Arming(String channelId, String command) {
    }

    /** 校时。 */
    record SyncTime(String channelId, LocalDateTime time) {
    }

    /** 事件订阅。 */
    record Subscribe(String channelId, String type, Integer expires, Integer interval) {
    }

    /** OSD 配置。 */
    record Osd(String channelId, Integer timeEnable, Integer timeType,
               Integer timeX, Integer timeY, Integer textEnable, String text,
               Integer textX, Integer textY, Integer width, Integer length) {
    }

    /** 抓拍配置。 */
    record Snapshot(String channelId, Integer snapNum, Integer interval, String uploadUrl) {
    }

    /** 设备升级。 */
    record Upgrade(String channelId, String firmware, String fileUrl, String manufacturer) {
    }

    /** 目标跟踪。 */
    record TargetTrack(String channelId, String mode, String deviceId2,
                       Integer length, Integer width,
                       Integer midpointX, Integer midpointY,
                       Integer lengthX, Integer lengthY) {
    }

    /** 报警通知下发。 */
    record AlarmNotify(String channelId, Integer priority, Integer method, Integer type,
                       String description, Double longitude, Double latitude) {
    }
}
