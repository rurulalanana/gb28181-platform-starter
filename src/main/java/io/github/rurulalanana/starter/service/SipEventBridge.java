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
            // 空目录不等于「什么都不用做」：下级把所有通道都删了时，
            // 应答就是 SumNum=0 + 空的 <DeviceList Num="0"/>。
            // 此时必须当作全量快照去清理，否则库里会永远留着幽灵通道。
            // 但只有当设备确实声明了 SumNum=0 才能确定是「真的空」；
            // 没带 SumNum 的空应答可能只是分页的最后一帧，不能据此删数据。
            Integer declared = msg.intOf("SumNum");
            if (declared != null && declared == 0) {
                log.info("收到空目录应答: device={} 按全量快照清理已下线通道", deviceId);
                asyncExecutor.run("空目录清理",
                        () -> deviceService.saveCatalog(deviceId, List.of(), true, 0, 1));
            } else {
                log.debug("目录应答无 Item: device={} xml={}", deviceId, event.getXml());
            }
            return;
        }
        // 分页聚合：下级平台（如 LiveGBS）常按标准一帧一页上报，
        // 不聚合会把「3 个通道」当成 3 次「只有 1 个通道的全量目录」
        Integer sumNum = msg.intOf("SumNum");
        CatalogPager.Result merged = catalogPager.accept(deviceId, msg.getSn(), sumNum, items);
        if (merged == null) {
            return;
        }
        asyncExecutor.run("目录同步落库", () -> {
            // 应答里混有 <Event>DELETE</Event> 时也要真正删通道：
            // 收齐时全量快照逻辑本就会删掉未上报的通道，但没收齐时只能靠这里。
            for (String removedId : merged.removed()) {
                deviceService.removeChannel(deviceId, removedId);
            }
            deviceService.saveCatalog(deviceId, merged.items(), merged.complete(),
                    merged.sumNum(), merged.frames());
        });
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
                    // 目录项变更类型（ADD/UPDATE/DELETE/ON/OFF），仅通知类报文携带
                    .event(text(item, "Event"))
                    .build();
            if (ci.getDeviceId() != null && !ci.getDeviceId().isBlank()) {
                out.add(ci);
            }
        }
        return out;
    }

    // ==================== 订阅推送（SIP NOTIFY） ====================

    /**
     * 处理 SIP {@code NOTIFY}（标准 9.11 / RFC 6665）。
     *
     * <p><b>为什么必须在这里解析消息体</b>：国标订阅推送有两条完全不同的信令路径，
     * 二者在协议栈里分派到不同回调，不能只实现其中一条：
     * <ul>
     *   <li>{@code MESSAGE} + MANSCDP 体 → {@link #onManscdpRequest}</li>
     *   <li>{@code NOTIFY} + MANSCDP 体 → <b>本方法</b></li>
     * </ul>
     * 早先本方法是空实现（注释误以为 NOTIFY 体也会走 onManscdpRequest），
     * 结果所有走 NOTIFY 的目录/报警推送被静默丢弃 —— 表现为
     * 「下级点了推送通道，本级毫无反应」，只能靠重新注册触发全量目录才能看到变化。
     *
     * <p>NOTIFY 与 MESSAGE 的语义一致：都是<b>增量通知</b>，不能当全量快照删旧数据。
     */
    @Override
    public void onNotify(io.github.rurulalanana.gb28181.sip.event.SubscribeEvent event) {
        String body = event == null ? null : event.getBody();
        if (body == null || body.isBlank()) {
            log.debug("NOTIFY 无消息体: peer={} event={}", 
                    event == null || event.getPeer() == null ? null : event.getPeer().getDeviceId(),
                    event == null ? null : event.getEventName());
            return;
        }
        ManscdpMessage msg;
        try {
            msg = io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml.parse(body);
        } catch (Exception e) {
            // 订阅确认等非 MANSCDP 体（如纯 RFC 6665）解析失败属正常，降为 debug
            log.debug("NOTIFY 消息体非 MANSCDP，忽略: event={} err={}",
                    event.getEventName(), e.getMessage());
            return;
        }
        String cmdType = msg.getCmdType();
        if (cmdType == null) {
            return;
        }
        String deviceId = msg.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            // 部分平台在 NOTIFY 里不带 DeviceID，退化为用 From 头里的设备编码
            deviceId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        }
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("NOTIFY 无法确定设备编码: event={} cmd={}", event.getEventName(), cmdType);
            return;
        }
        final String from = deviceId;
        switch (cmdType) {
            case "Catalog" -> handlePushedCatalog(from, msg, event.getEventName());
            case "Alarm" -> asyncExecutor.run("报警推送落库",
                    () -> alarmService.onAlarmNotify(from, msg));
            default -> log.debug("NOTIFY 未处理的命令: cmd={} device={}", cmdType, from);
        }
    }

    /**
     * 处理推送来的目录（MESSAGE 与 NOTIFY 共用）。
     *
     * <p>推送是<b>增量通知</b>：只包含发生变化的条目，因此恒以
     * {@code fullSnapshot=false} 落库，只做 upsert，绝不清理未提到的通道。
     *
     * <p>唯一例外是 {@code <Event>DELETE</Event>}：它明确表示下级删除了该通道，
     * 必须真正从库里删除，否则幽灵通道永远留着。
     */
    private void handlePushedCatalog(String deviceId, ManscdpMessage msg, String source) {
        List<CatalogItem> items = parseCatalog(msg);
        if (items.isEmpty()) {
            log.debug("目录推送无 Item: device={} source={}", deviceId, source);
            return;
        }
        Integer sumNum = msg.intOf("SumNum");
        CatalogPager.Result merged = catalogPager.accept(deviceId, msg.getSn(), sumNum, items);
        if (merged == null) {
            return;
        }
        log.info("收到目录变更推送: device={} 来源={} 条目={} 删除={} 下级自报={} 帧数={}",
                deviceId, source, merged.items().size(), merged.removed().size(),
                merged.sumNum(), merged.frames());
        // 先删后存：同一批次里若先 ADD 后 DELETE，聚合器已保证只剩删除项；
        // 反之（先 DELETE 后 ADD）则只剩 upsert 项，两者不会同时命中同一编码。
        asyncExecutor.run("目录推送落库", () -> {
            for (String removedId : merged.removed()) {
                deviceService.removeChannel(deviceId, removedId);
            }
            if (!merged.items().isEmpty()) {
                deviceService.saveCatalog(deviceId, merged.items(), false,
                        merged.sumNum(), merged.frames());
            }
        });
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
            // 设备/下级平台主动上报目录变更（MESSAGE + Notify/Catalog）。
            //
            // 【关键】这与 Query 应答的语义完全不同：
            // - Response 是「对查询的完整答复」，收齐后可当全量快照
            // - Notify 是「目录发生变化的增量通知」，只包含变化的那几条
            // 若把增量当全量，removeChannelsNotIn 会把本次没提到的通道全部误删。
            // 因此恒以 fullSnapshot=false 落库，只做 upsert，绝不删旧通道。
            String deviceId = msg.getDeviceId();
            if (deviceId != null && !deviceId.isBlank()) {
                handlePushedCatalog(deviceId, msg, "MESSAGE");
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
