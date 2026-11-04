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
import io.github.rurulalanana.starter.common.Numbers;
import io.github.rurulalanana.starter.common.TimeFormats;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信令事件 → 持久化的桥接器：平台启动时通过 {@code setCallbackDelegate} 挂到
 * {@code Gb28181MediaService} 上，即可不改依赖模块而感知注册、心跳、目录、报警、级联等事件。
 *
 * <p>回调在 SIP 协议栈线程上执行，禁止阻塞：这里只做「解析 + 单条 insert/update」，
 * 耗时操作（如全量目录同步）交给 {@link PlatformAsyncExecutor}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SipEventBridge implements SipCallback {

    private final Gb28181MediaPlatform platform;
    private final DeviceService deviceService;
    private final AlarmService alarmService;
    private final CascadeService cascadeService;
    /** 上行链路：回复上级的目录查询。 */
    private final CascadeLinkService cascadeLinkService;
    private final DeviceCollectService collectService;
    private final SipRegistryHealer registryHealer;
    private final PlatformAsyncExecutor asyncExecutor;
    /** 目录分页聚合（下级平台按 SumNum 一帧一页上报）。 */
    private final CatalogPager catalogPager;
    /** 预置位查询应答的落缓存与回显。 */
    private final PresetService presetService;
    /** 控制应答（A.2.6.2）的等待位，让控制类接口能拿到设备真实回话。 */
    private final ControlAckService controlAckService;

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

    /**
     * 这个来源值不值得继续打交道。SIP 端口开在公网后扫描器会不断发 REGISTER / MESSAGE，
     * 编号是随手编的（{@code .} / {@code 059} / {@code 9999}）；若照旧去自动采集，
     * 每条 MESSAGE 都要等满 32 秒事务超时。不能用 {@code registry} 判断（它已把对方当合法来源收下）。
     * 合法 20 位编码直接放行，只在格式非法时查一次库 —— 手工添加的自定义编号仍会被采集。
     */
    private boolean acceptable(String deviceId) {
        if (io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(deviceId)) {
            return true;
        }
        boolean known = deviceService.findDevice(deviceId) != null;
        if (!known) {
            log.debug("跳过非法设备编码（公网扫描器常见）: {}", deviceId);
        }
        return known;
    }

    @Override
    public void onRegister(RegisterEvent event) {
        String deviceId = event.getDeviceId();
        asyncExecutor.run("设备注册落库", () -> deviceService.onRegister(event));
        if (event.isUnregistered()) {
            // 注销后清节流，下次上线立即重新采集
            collectService.forget(deviceId);
            // 同时丢弃未收口的目录批次，避免旧数据被当成新一轮结果
            catalogPager.forget(deviceId);
            return;
        }
        // 注册完成后自动采集：目录/信息/状态
        if (acceptable(deviceId)) {
            collectService.collectOnOnline(deviceId, false);
        }
    }

    @Override
    public void onKeepAlive(KeepAliveEvent event) {
        if (!acceptable(event.getDeviceId())) {
            return;
        }
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
            case "MobilePosition" -> asyncExecutor.run("移动位置落库",
                    () -> deviceService.savePosition(msg.getDeviceId(), msg.getDeviceId(), msg));
            /*
             * 预置位查询应答（A.2.4.8）。此前这里是空的：能下发查询但应答被 default 静默丢弃，
             * 表现为「点了查询预置位，什么都没发生」。
             * 传 {@code msg.getSn()}：设备会把预置位拆成多帧上报（实测 5 个发两帧 4+1），
             * 同一次查询的各帧 SN 相同，PresetService 靠它区分后续帧与新查询，否则后帧覆盖前帧。
             * 用 {@code elements("PresetList")} 而非 {@code items(...)}：后者只取第一个容器。
             */
            case "PresetQuery" -> asyncExecutor.run("预置位落缓存",
                    () -> presetService.onPresetList(msg.getDeviceId(),
                            msg.getSn(), msg.elements("PresetList")));
            /*
             * 设备控制应答（A.2.6.2）。{@code Result} 只表示「命令受不受理」，
             * 不是「动作做完没有」；但它是平台唯一能拿到的设备回话。
             * 目标跟踪、拉框缩放、设备升级靠它把「已下发」变成「设备说了什么」。
             */
            case "DeviceControl" -> controlAckService.onResponse(
                    msg.getDeviceId(), msg.getSn(), msg.firstText("Result"));
            /*
             * 设备软件升级结果（A.2.5.9）。升级要下载固件再刷写，时长从几分钟到几十分钟，
             * 没法同步等，只能靠这条通知收尾。
             */
            case "DeviceUpgradeResult" -> log.info(
                    "设备升级结果: device={} session={} result={} firmware={} 失败原因={}",
                    msg.getDeviceId(), msg.firstText("SessionID"), msg.firstText("UpgradeResult"),
                    msg.firstText("Firmware"), msg.firstText("UpgradeFailedReason"));
            default -> {
                // DeviceStatus / RecordInfo / ConfigDownload 等由各自服务同步等待；打一条 DEBUG
                // 让未适配的私有命令能被看见，否则「设备回了、平台说没有」只能靠抓包。
                log.debug("未处理的 MANSCDP 应答: cmd={} device={} 子元素={}",
                        cmdType, msg.getDeviceId(), msg.childNames());
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
            // 空目录不等于「什么都不用做」：下级删光通道时就是 SumNum=0 + 空 DeviceList，
            // 必须当全量快照清理，否则幽灵通道永远留着；但只有声明 SumNum=0 才能确定是真的空，
            // 没带 SumNum 的空应答可能只是分页最后一帧。
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
        // 不聚合会把「3 个通道」读成 3 次「只有 1 个通道的全量目录」
        Integer sumNum = msg.intOf("SumNum");
        CatalogPager.Result merged = catalogPager.accept(deviceId, msg.getSn(), sumNum, items);
        if (merged == null) {
            return;
        }
        asyncExecutor.run("目录同步落库", () -> {
            // 应答里混有 <Event>DELETE</Event> 时也要真正删：收齐时全量快照本就会删未上报通道，
            // 没收齐时只能靠这里。
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
     * 处理 SIP {@code NOTIFY}（标准 9.11 / RFC 6665）。国标订阅推送有两条信令路径，
     * {@code MESSAGE} 走 {@link #onManscdpRequest}，{@code NOTIFY} 走本方法，不能只实现一条。
     * 早先本方法为空（误以为 NOTIFY 体也走 onManscdpRequest），所有走 NOTIFY 的目录 / 报警推送
     * 被静默丢弃。语义与 MESSAGE 一致：增量通知，不能当全量快照删旧数据。
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
            case "MobilePosition" -> asyncExecutor.run("移动位置落库",
                    () -> deviceService.savePosition(from, from, msg));
            /*
             * 升级结果也有厂家走 NOTIFY 而不是 MESSAGE（标准只规定它是 A.2.5 通知命令，
             * 没规定承载在哪个 SIP 方法上），两条路都要接。
             */
            case "DeviceUpgradeResult" -> log.info(
                    "设备升级结果: device={} session={} result={} firmware={} 失败原因={}",
                    from, msg.firstText("SessionID"), msg.firstText("UpgradeResult"),
                    msg.firstText("Firmware"), msg.firstText("UpgradeFailedReason"));
            default -> log.debug("NOTIFY 未处理的命令: cmd={} device={}", cmdType, from);
        }
    }

    /**
     * 处理推送来的目录（MESSAGE 与 NOTIFY 共用）：推送是增量通知，恒按
     * {@code fullSnapshot=false} 落库，只做 upsert，绝不清理未提到的通道。
     * 唯一例外是 {@code <Event>DELETE</Event>}：明确表示下级删了该通道，必须真删。
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
        // 先删后存：聚合器已保证同批次里删除项与 upsert 项不会命中同一编码
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
            /*
             * 两种语义不同的 MANSCDP，必须按 rootName 分流：{@code <Query>} 是上级来查我们的目录，
             * 必须应答（否则上级目录页永远空白）；{@code <Notify>} 是下级推目录变更，按增量 upsert。
             * {@code <Response>} 走 onManscdpResponse 的 handleCatalog（才有分页聚合）。
             */
            if ("Query".equals(msg.getRootName())) {
                handleCatalogQuery(event);
            } else {
                String deviceId = msg.getDeviceId();
                if (deviceId != null && !deviceId.isBlank()) {
                    handlePushedCatalog(deviceId, msg, msg.getRootName());
                }
            }
        } else if ("DeviceControl".equals(cmdType)) {
            /*
             * 上级下发控制（云台、预置位、拉框缩放、布撤防）。此前这里没有分支：
             * 既不给上级回 Response，也不转给设备，现象是上级点云台一直转圈报「控制失败」而设备没动。
             * 只处理 {@code <Control>}：{@code <Response>} 形式的 DeviceControl 是设备对应答，走 onManscdpResponse。
             */
            if ("Control".equals(msg.getRootName())) {
                handleCascadeControl(event);
            }
        }
        return null;
    }

    /**
     * 转发上级的控制指令给设备，并立即应答上级（{@code OK} 语义是「已受理」）。
     * 识别来源靠 From 头的平台编码比对 {@code parentId}：上级永远不注册到本级。
     * 不从设备应答再回上级：设备对云台指令普遍不做应用层应答，等它必然超时，而云台已经动了。
     * 异步执行：转发要查库，而本方法跑在 jain-sip 协议栈线程上。
     */
    private void handleCascadeControl(ManscdpEvent event) {
        String fromId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        if (fromId == null || fromId.isBlank()) {
            log.debug("控制指令来源为空，忽略");
            return;
        }
        ManscdpMessage msg = event.getManscdp();
        int sn = msg.getSn();
        String channelId = msg.getDeviceId();
        if (channelId == null || channelId.isBlank()) {
            log.warn("控制指令缺少 DeviceID，忽略: from={}", fromId);
            return;
        }
        asyncExecutor.run("级联控制转发", () -> {
            var cascade = cascadeService.findByParentId(fromId);
            if (cascade == null) {
                // 不认识的来源：既不转发也不应答，避免变成对外开放的控制入口
                log.debug("控制指令来自未配置的平台 {}，忽略", fromId);
                return;
            }
            if (!Boolean.TRUE.equals(cascade.getEnabled())) {
                log.debug("级联 {} 未启用，忽略其控制指令", fromId);
                return;
            }
            boolean ok = cascadeLinkService.forwardControl(
                    cascade.getCascadeId(), channelId, msg);
            cascadeLinkService.replyControl(cascade.getCascadeId(), sn, channelId, ok);
            if (!ok) {
                log.warn("级联控制未转发: 上级={} 通道={} sn={}", fromId, channelId, sn);
            }
        });
    }

    /**
     * 应答上级的目录查询。靠 From 头的平台编码识别来源（上级不注册到本级，注册表查不到它），
     * 不校验来源等于对任意 IP 开放本级通道清单；异步执行避免阻塞 jain-sip 线程。
     */
    private void handleCatalogQuery(ManscdpEvent event) {
        String fromId = event.getPeer() == null ? null : event.getPeer().getDeviceId();
        int sn = event.getManscdp().getSn();
        String targetId = event.getManscdp().getDeviceId();
        if (fromId == null || fromId.isBlank()) {
            log.debug("目录查询来源为空，忽略");
            return;
        }
        asyncExecutor.run("级联目录查询应答", () -> {
            var cascade = cascadeService.findByParentId(fromId);
            if (cascade == null) {
                log.debug("目录查询来自未配置的平台 {}，不应答（避免信息泄漏）", fromId);
                return;
            }
            if (!Boolean.TRUE.equals(cascade.getEnabled())) {
                log.debug("级联 {} 未启用，忽略其目录查询", fromId);
                return;
            }
            cascadeLinkService.replyCatalogQuery(cascade.getCascadeId(), sn, targetId);
        });
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

    /**
     * 下级平台注册到本级。绝不能改 {@code gb_cascade.online}：本回调是「有平台注册进来」（我们的下级），
     * 而 {@code online} 是「我们注册出去」是否成功（我们的上级）。
     * 改造前这里按 {@code parentId} 反查配置并置在线，于是不相干的下级注册进来、
     * 只要恰好配了同编码的上级，界面就显示在线，掩盖了真实的上行注册失败。
     * 上行状态由 {@link CascadeLinkService} 维护。
     */
    @Override
    public void onCascadePlatformRegister(CascadeEvents.PlatformRegistered event) {
        // 只记日志：下级注册进来是「入站」事件，与上行注册状态无关
        log.info("下级平台注册进来: {} ({})", event.getPlatformId(), event.getAddress());
    }

    /** 下级平台注销（同上：不影响上行注册状态）。 */
    @Override
    public void onCascadePlatformUnregister(CascadeEvents.PlatformUnregistered event) {
        log.info("下级平台注销: {}（{}）", event.getPlatformId(), event.getReason());
    }

    /**
     * 上级平台点播本级通道：启动本地流并把 RTP 推给上级。必须异步：启动流要邀请设备并等它推 PS
     * （实测 1~5 秒），在 jain-sip 协议栈线程上等几秒会把整个信令栈拖死，所有设备心跳都超时。
     */
    @Override
    public void onUplinkCascadePlay(CascadeEvents.UplinkPlay event) {
        if (event == null) {
            return;
        }
        asyncExecutor.run("级联上行推流", () -> {
            boolean ok = cascadeLinkService.pushToUplink(
                    event.getPlatformId(), event.getChannelId(),
                    event.getDstIp(), event.getDstPort(),
                    event.getSsrc(), event.isTcp());
            if (!ok) {
                log.warn("级联上行推流未完成: 上级={} 通道={} 目标={}:{} 传输={}",
                        event.getPlatformId(), event.getChannelId(),
                        event.getDstIp(), event.getDstPort(),
                        event.isTcp() ? "TCP" : "UDP");
            }
        });
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
        return Numbers.intOrNull(text(parent, tag));
    }

    private static Integer intOfChild(org.w3c.dom.Element parent, String container, String tag) {
        return Numbers.intOrNull(textChild(parent, container, tag));
    }

    private static Double doubleOf(org.w3c.dom.Element parent, String tag) {
        return Numbers.doubleOrNull(text(parent, tag));
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
