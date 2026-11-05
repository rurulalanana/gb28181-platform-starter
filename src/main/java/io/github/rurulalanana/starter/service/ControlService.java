package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.service.control.DeviceControl;
import io.github.rurulalanana.starter.service.control.DeviceControlResolver;
import io.github.rurulalanana.starter.vo.ControlResultVo;
import io.github.rurulalanana.starter.vo.DeviceCapabilitiesVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 设备控制入口（GB/T 28181—2022 附录 A.2.3 Control）。
 *
 * <h3>本类现在只做一件事：把参数装进命令对象，交给对应协议的实现</h3>
 * 改造前它有 1029 行、32 个方法，其中 {@code providerOf(...) != null} 出现 31 次，
 * 每个方法都是「判协议 → 走国标 / 走 SDK」的双份逻辑，还漏了 2 个
 * （{@code ptzPrecise} 与 {@code subscribe} 没有分支，把 SDK 设备当国标往 SIP 里发）。
 *
 * <p>现在分流由 {@link DeviceControlResolver} 在入口处完成一次，协议差异全部下沉到：
 * <ul>
 *   <li>{@code Gb28181DeviceControl}——国标 MANSCDP 报文（30 项能力全部实现）；</li>
 *   <li>{@code DahuaDeviceControl}——大华 SDK（只覆写做得到的，其余继承默认异常）。</li>
 * </ul>
 *
 * <p><b>方法签名保持不变</b>，是为了让 5 个 Controller 与前端零改动 ——
 * 重构可以独立验证，不必和接口调整捆在一起。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlService {

    private final DeviceControlResolver resolver;

    // ==================== 云台 ====================

    /** 云台方向控制。{@code speed} 0~255，默认 129。 */
    public void ptz(String channelId, String command, Integer speed) {
        resolver.of(channelId).ptz(new DeviceControl.Ptz(channelId, command, speed));
    }

    /** 变倍 / 聚焦 / 光圈。 */
    public void focusIris(String channelId, String command, Integer speed) {
        resolver.of(channelId).focusIris(new DeviceControl.FocusIris(channelId, command, speed));
    }

    /** 精确云台控制（2022）：角度 + 变焦倍数。 */
    public void ptzPrecise(String channelId, Double pan, Double tilt, Double zoom) {
        resolver.of(channelId).ptzPrecise(
                new DeviceControl.PtzPrecise(channelId, pan, tilt, zoom));
    }

    /** 拉框放大 / 缩小（A.2.3.1.8 / A.2.3.1.9）。 */
    public ControlResultVo dragZoom(String channelId, boolean zoomIn, int length, int width,
                                    int midpointX, int midpointY, int lengthX, int lengthY) {
        return resolver.of(channelId).dragZoom(new DeviceControl.DragZoom(
                channelId, zoomIn, length, width, midpointX, midpointY, lengthX, lengthY));
    }

    // ==================== 预置位 ====================

    /** 预置位控制：{@code set} / {@code goto} / {@code remove}。 */
    public void preset(String channelId, String command, Integer preset) {
        resolver.of(channelId).preset(new DeviceControl.Preset(channelId, command, preset));
    }

    /** 触发预置位清单查询（国标异步下发；SDK 设备为同步读，实现为空操作）。 */
    public void queryPresets(String channelId) {
        resolver.of(channelId).queryPresets(channelId);
    }

    // ==================== 巡航 / 看守位 ====================

    /** 巡航控制：{@code add}/{@code remove}/{@code setspeed}/{@code setstay}/{@code start}/{@code stop}/{@code query}。 */
    public void navigate(String channelId, String command, Integer groupId, Integer preset,
                         Integer speed, Integer stay, String name) {
        resolver.of(channelId).cruise(new DeviceControl.Cruise(
                channelId, command, groupId, preset, speed, stay, name));
    }

    /** 查询巡航轨迹。 */
    public void queryCruiseTrack(String channelId, Integer number) {
        resolver.of(channelId).queryCruiseTrack(channelId, number);
    }

    /** 查询看守位信息。 */
    public void queryHomePosition(String channelId) {
        resolver.of(channelId).queryHomePosition(channelId);
    }

    /** 看守位控制。 */
    public void homePosition(String channelId, Boolean enabled, Integer resetTime,
                             Integer presetIndex) {
        resolver.of(channelId).homePosition(
                new DeviceControl.HomePosition(channelId, enabled, resetTime, presetIndex));
    }

    // ==================== 辅助开关 ====================

    /** 雨刷开关（开关编号固定 1）。 */
    public void wiper(String channelId, String command) {
        resolver.of(channelId).wiper(channelId, command);
    }

    /** 补光灯开关（编号由设备定义，默认 2）。 */
    public void fillLight(String channelId, String command, Integer index) {
        resolver.of(channelId).fillLight(channelId, command, index);
    }

    // ==================== 设备控制 ====================

    /** 设备端录像控制：{@code start} / {@code stop}。 */
    public void recordControl(String channelId, String command, Integer streamNumber) {
        resolver.of(channelId).recording(
                new DeviceControl.Recording(channelId, command, streamNumber));
    }

    /** 布防 / 撤防。 */
    public void guard(String channelId, String command) {
        resolver.of(channelId).guard(new DeviceControl.Arming(channelId, command));
    }

    /**
     * 设备远程重启（A.2.3.1.3）。
     *
     * <p>SDK 直连设备重启后会断线几十秒，期间预览与录像全部中断。
     */
    public void teleBoot(String channelId) {
        resolver.of(channelId).reboot(channelId);
    }

    /**
     * 强制关键帧（A.2.3.1.7）。
     *
     * <p>大华 NetSDK 没有该接口，由 {@code DahuaDeviceControl} 的能力表拒绝，
     * 原因文案在它的 {@code unsupportedHint} 里 —— 不在本类拼字符串。
     */
    public void forceIFrame(String channelId) {
        resolver.of(channelId).forceIFrame(channelId);
    }

    /** 存储卡格式化（A.2.3.1.13）：{@code id=0} 表示全部。 */
    public void formatSdCard(String channelId, Integer sdCardId) {
        resolver.of(channelId).formatSdCard(channelId, sdCardId);
    }

    /** 报警复位（A.2.3.1.9）。 */
    public void alarmReset(String channelId) {
        resolver.of(channelId).alarmReset(channelId);
    }

    /**
     * 设备校时。设备时钟漂移会让录像 {@code StartTime/EndTime} 与平台检索区间对不上。
     *
     * @param localTime 要下发的时间；{@code null} 表示平台当前时间
     */
    public void syncTime(String channelId, LocalDateTime localTime) {
        resolver.of(channelId).syncTime(new DeviceControl.SyncTime(channelId, localTime));
    }

    /** 设备配置下发（2022）：直接透传 xml。 */
    public void deviceConfig(String channelId, String xml) {
        resolver.of(channelId).deviceConfig(channelId, xml);
    }

    /** 设备订阅（目录 / 报警 / 位置 / PTZ）。 */
    public void subscribe(String channelId, String type, Integer expires, Integer interval) {
        resolver.of(channelId).subscribe(
                new DeviceControl.Subscribe(channelId, type, expires, interval));
    }

    // ==================== 查询 ====================

    /** 查询设备状态（标准 9.6）。 */
    public void queryDeviceStatus(String channelId) {
        resolver.of(channelId).queryDeviceStatus(channelId);
    }

    /** 查询设备信息（标准 9.5）。 */
    public void queryDeviceInfo(String channelId) {
        resolver.of(channelId).queryDeviceInfo(channelId);
    }

    /** 查询设备配置（标准 A.2.4.7）。 */
    public void queryConfig(String channelId, String configType) {
        resolver.of(channelId).queryConfig(channelId, configType);
    }

    /** 查询存储卡状态（2022）。 */
    public void querySdCardStatus(String channelId) {
        resolver.of(channelId).querySdCardStatus(channelId);
    }

    /** 查询 PTZ 精确状态（2022）。 */
    public void queryPtzPosition(String channelId) {
        resolver.of(channelId).queryPtzPosition(channelId);
    }

    // ==================== OSD / 抓拍 / 升级 / 目标跟踪 ====================

    /** 前端 OSD 配置（2022）。 */
    public void osdConfig(String channelId, Integer timeEnable, Integer timeType,
                          Integer timeX, Integer timeY, Integer textEnable, String text,
                          Integer textX, Integer textY, Integer width, Integer length) {
        resolver.of(channelId).osdConfig(new DeviceControl.Osd(channelId,
                timeEnable, timeType, timeX, timeY, textEnable, text, textX, textY, width, length));
    }

    /** 图像抓拍配置（2022）。 */
    public void snapshotConfig(String channelId, Integer snapNum, Integer interval,
                               String uploadUrl) {
        resolver.of(channelId).snapshotConfig(
                new DeviceControl.Snapshot(channelId, snapNum, interval, uploadUrl));
    }

    /** 设备软件升级（A.2.3.1.12）。命令发出即返回，最终结果由设备另行通知。 */
    public ControlResultVo deviceUpgrade(String channelId, String firmware,
                                         String fileUrl, String manufacturer) {
        return resolver.of(channelId).deviceUpgrade(
                new DeviceControl.Upgrade(channelId, firmware, fileUrl, manufacturer));
    }

    /** 目标跟踪控制（A.2.3.1.14）。 */
    public ControlResultVo targetTrack(String channelId, String targetTrack, String deviceId2,
                                       Integer length, Integer width,
                                       Integer midpointX, Integer midpointY,
                                       Integer lengthX, Integer lengthY) {
        return resolver.of(channelId).targetTrack(new DeviceControl.TargetTrack(
                channelId, targetTrack, deviceId2, length, width,
                midpointX, midpointY, lengthX, lengthY));
    }

    // ==================== 报警通知 / 喊话 ====================

    /** 报警通知下发（标准 A.2.5.9）。 */
    public void alarmNotify(String channelId, Integer priority, Integer method, Integer type,
                            String description, Double longitude, Double latitude) {
        resolver.of(channelId).alarmNotify(new DeviceControl.AlarmNotify(
                channelId, priority, method, type, description, longitude, latitude));
    }

    /** 语音喊话（标准 A.2.3.1.11 Broadcast）。 */
    public void talk(String channelId, String sourceId) {
        resolver.of(channelId).talk(channelId, sourceId);
    }

    // ==================== 能力表 ====================

    /**
     * 该设备支持哪些控制能力。
     *
     * <p>前端据此把按钮置灰并显示原因，而不是让用户点完再收一个 400。
     * 这是统一接口带来的额外收益：改造前能力信息只存在于 31 个 {@code if} 分支里。
     */
    public DeviceCapabilitiesVo capabilities(String deviceId) {
        DeviceControl control = resolver.ofDeviceId(deviceId);
        return DeviceCapabilitiesVo.of(control.kind(), control.capabilities(),
                control::unsupportedHint);
    }
}
