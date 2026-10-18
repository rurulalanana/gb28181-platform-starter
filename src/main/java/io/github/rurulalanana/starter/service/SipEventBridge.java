package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.starter.common.TimeFormats;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信令事件 → 持久化 的桥接器。
 *
 * <p>{@code gb28181-sip-zlm} 的 {@code Gb28181MediaService} 是 SIP 回调的唯一接收者，
 * 平台启动时通过 {@code setCallbackDelegate} 挂上本类，即可在不改动依赖模块的前提下
 * 感知注册、心跳、目录应答、报警通知、级联注册等事件并落库。
 *
 * <p><b>线程模型</b>：回调在 SIP 协议栈线程上执行，禁止阻塞。
 * 因此这里只做「解析 + 单条 insert/update」，耗时操作（如全量目录同步）交给
 * {@link PlatformAsyncExecutor}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SipEventBridge implements SipCallback {

    private final Gb28181MediaPlatform platform;
    private final DeviceService deviceService;
    private final AlarmService alarmService;
    private final CascadeService cascadeService;
    private final DeviceCollectService collectService;
    private final SipRegistryHealer registryHealer;
    private final PlatformAsyncExecutor asyncExecutor;
    /** 目录分页聚合（下级平台按 SumNum 一帧一页上报）。 */
    private final CatalogPager catalogPager;

    /** 平台启动后把本对象挂为信令回调委派。 */
    @PostConstruct
    public void attach() {
        if (platform.mediaService() == null) {
            log.warn("媒体服务尚未就绪，跳过信令回调挂载");
            return;
        }
        platform.mediaService().setCallbackDelegate(this);
        log.info("信令事件桥已挂载（注册/心跳/目录/报警/级联 将落库）");
    }

    // ==================== 注册 / 心跳 ====================

    @Override
    public void onRegister(RegisterEvent event) {
        String deviceId = event.getDeviceId();
        asyncExecutor.run("设备注册落库", () -> deviceService.onRegister(event));
        // 注册完成后自动采集：目录/信息/状态
        if (event.isUnregistered()) {
            // 注销后清节流，下次上线立即重新采集
            collectService.forget(deviceId);
            // 同时丢弃未收口的目录批次，避免旧数据被当成新一轮结果
            catalogPager.forget(deviceId);
        } else {
            collectService.collectOnOnline(deviceId, false);
        }
    }

    @Override
    public void onKeepAlive(KeepAliveEvent event) {
        // 平台重启后注册表为空，靠心跳自愈重建会话，否则后续下行信令全部失败
        boolean healed = registryHealer.healOnKeepAlive(event);
        asyncExecutor.run("设备心跳落库", () -> deviceService.onKeepAlive(event));
        if (healed) {
            // 刚恢复可达性，顺便补一次数据采集（内部有节流，不会重复打扰设备）
            collectService.collectOnOnline(event.getDeviceId(), false);
        }
    }

    // ==================== MANSCDP ====================

    @Override
    public void onManscdpResponse(ManscdpEvent event) {
        ManscdpMessage msg = event == null ? null : event.getManscdp();
        if (msg == null) {
            return;
        }
        String cmdType = msg.getCmdType();
        if (cmdType == null) {
            return;
        }
        switch (cmdType) {
            case "Catalog" -> handleCatalog(event, msg);
            case "DeviceInfo" -> asyncExecutor.run("设备信息落库",
                    () -> deviceService.saveDeviceInfo(msg.getDeviceId(), msg));
            case "Alarm" -> asyncExecutor.run("报警复位处理",
                    () -> alarmService.onAlarmReset(msg.getDeviceId(), msg));
            default -> {
                // DeviceStatus / RecordInfo / ConfigDownload 等由各自的服务同步等待处理
            }
        }
    }

    /** 目录应答：按 SumNum 聚合分页后落库设备与通道。 */
    private void handleCatalog(ManscdpEvent event, ManscdpMessage msg) {
        String deviceId = msg.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        List<CatalogItem> items = parseCatalog(msg);
        if (items.isEmpty()) {
            log.debug("目录应答无 Item: device={} xml={}", deviceId, event.getXml());
            return;
        }
        // 分页聚合：下级平台（如 LiveGBS）常按标准一帧一页上报，
        // 不聚合会把「3 个通道」当成 3 次「只有 1 个通道的全量目录」
        Integer sumNum = msg.intOf("SumNum");
        CatalogPager.Result merged = catalogPager.accept(deviceId, msg.getSn(), sumNum, items);
        if (merged == null) {
            return;
        }
        asyncExecutor.run("目录同步落库",
                () -> deviceService.saveCatalog(deviceId, merged.items(), merged.complete(),
                        merged.sumNum(), merged.frames()));
    }

    /** 把 MANSCDP 的 {@code <DeviceList><Item>} 解析为 {@link CatalogItem}。 */
    static List<CatalogItem> parseCatalog(ManscdpMessage msg) {
        List<CatalogItem> out = new java.util.ArrayList<>();
        for (org.w3c.dom.Element item : msg.items("DeviceList")) {
            CatalogItem ci = CatalogItem.builder()
                    .deviceId(text(item, "DeviceID"))
                    .name(text(item, "Name"))
                    .manufacturer(text(item, "Manufacturer"))
                    .model(text(item, "Model"))
                    .civilCode(text(item, "CivilCode"))
                    .block(text(item, "Block"))
                    .address(text(item, "Address"))
                    .parental(intOf(item, "Parental"))
                    .parentId(text(item, "ParentID"))
                    .registerWay(intOf(item, "RegisterWay"))
                    .secrecy(intOf(item, "Secrecy"))
                    .ipAddress(text(item, "IPAddress"))
                    .port(intOf(item, "Port"))
                    .password(text(item, "Password"))
                    .status(text(item, "Status"))
                    .longitude(doubleOf(item, "Longitude"))
                    .latitude(doubleOf(item, "Latitude"))
                    .businessGroupId(text(item, "BusinessGroupID"))
                    // Info 容器内的扩展字段
                    .ptzType(intOfChild(item, "Info", "PTZType"))
                    .roomType(intOfChild(item, "Info", "RoomType"))
                    .supplyLightType(intOfChild(item, "Info", "SupplyLightType"))
                    .directionType(intOfChild(item, "Info", "DirectionType"))
                    .resolution(textChild(item, "Info", "Resolution"))
                    .downloadSpeed(textChild(item, "Info", "DownloadSpeed"))
                    .encodeType(textChild(item, "Info", "EncodeType"))
                    .installTime(textChild(item, "Info", "InstallTime"))
                    .managementUnit(textChild(item, "Info", "ManagementUnit"))
                    .contactInfo(textChild(item, "Info", "ContactInfo"))
                    .recordSaveDays(intOfChild(item, "Info", "RecordSaveDays"))
                    .build();
            if (ci.getDeviceId() != null && !ci.getDeviceId().isBlank()) {
                out.add(ci);
            }
        }
        return out;
    }

    // ==================== 报警通知 ====================

    @Override
    public void onNotify(io.github.rurulalanana.gb28181.sip.event.SubscribeEvent event) {
        // 设备侧主动上报（订阅推送）走这里；实际报警体在 onManscdpRequest 分支处理
    }

    @Override
    public Boolean onManscdpRequest(ManscdpEvent event) {
        ManscdpMessage msg = event == null ? null : event.getManscdp();
        if (msg == null) {
            return null;
        }
        String cmdType = msg.getCmdType();
        if ("Alarm".equals(cmdType)) {
            asyncExecutor.run("报警落库",
                    () -> alarmService.onAlarmNotify(msg.getDeviceId(), msg));
        } else if ("Keepalive".equals(cmdType)) {
            // 心跳已由 gb28181-sip 自动刷新注册表，此处无需重复处理
            return null;
        } else if ("Catalog".equals(cmdType)) {
            // 设备主动上报目录变更（订阅推送）：同样可能是分页的，走聚合器
            List<CatalogItem> items = parseCatalog(msg);
            if (!items.isEmpty()) {
                String deviceId = msg.getDeviceId();
                Integer sumNum = msg.intOf("SumNum");
                CatalogPager.Result merged = catalogPager.accept(deviceId, msg.getSn(), sumNum, items);
                if (merged != null) {
                    asyncExecutor.run("目录推送落库",
                            () -> deviceService.saveCatalog(deviceId, merged.items(), merged.complete(),
                                    merged.sumNum(), merged.frames()));
                }
            }
        }
        return null;
    }

    // ==================== 点播 / 回放 ====================

    @Override
    public void onInvite(InviteEvent event) {
        log.debug("收到 INVITE: callId={} s={}", event.getCallId(),
                event.getSdp() == null ? null : event.getSdp().getSessionName());
    }

    @Override
    public void onBye(InviteEvent event) {
        log.debug("收到 BYE: callId={}", event.getCallId());
    }

    // ==================== 级联 ====================

    @Override
    public void onCascadePlatformRegister(CascadeEvents.PlatformRegistered event) {
        asyncExecutor.run("上级平台上线", () -> cascadeService.setOnline(event.getPlatformId(), true));
    }

    @Override
    public void onCascadePlatformUnregister(CascadeEvents.PlatformUnregistered event) {
        asyncExecutor.run("上级平台离线", () -> cascadeService.setOnline(event.getPlatformId(), false));
    }

    // ==================== 内部：XML 取值 ====================

    private static String text(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    /** 只在指定容器（如 {@code Info}）内查找，避免误命中同名字段。 */
    private static String textChild(org.w3c.dom.Element parent, String container, String tag) {
        var containers = parent.getElementsByTagName(container);
        if (containers.getLength() == 0) {
            return null;
        }
        var el = (org.w3c.dom.Element) containers.item(0);
        return text(el, tag);
    }

    private static Integer intOf(org.w3c.dom.Element parent, String tag) {
        return parseInt(text(parent, tag));
    }

    private static Integer intOfChild(org.w3c.dom.Element parent, String container, String tag) {
        return parseInt(textChild(parent, container, tag));
    }

    private static Double doubleOf(org.w3c.dom.Element parent, String tag) {
        String v = text(parent, tag);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 记录事件时间（供日志与状态日志使用）。 */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 格式化时间（供日志使用）。 */
    static String format(LocalDateTime time) {
        return TimeFormats.toDisplay(time);
    }
}
