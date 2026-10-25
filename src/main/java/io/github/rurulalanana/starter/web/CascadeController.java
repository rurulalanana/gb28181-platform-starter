package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.entity.GbCascade;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.service.CascadeLinkService;
import io.github.rurulalanana.starter.service.CascadeService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.vo.CascadeChannelListVo;
import io.github.rurulalanana.starter.vo.CascadeChannelVo;
import io.github.rurulalanana.starter.vo.CascadeListVo;
import io.github.rurulalanana.starter.vo.CascadeVo;
import io.github.rurulalanana.starter.vo.DeletedVo;
import io.github.rurulalanana.starter.vo.IdVo;
import io.github.rurulalanana.starter.vo.StatusLogListVo;
import io.github.rurulalanana.starter.vo.StatusLogVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 级联配置接口。
 *
 * <pre>
 * GET  /api/v1/cascade/list                级联配置列表
 * GET  /api/v1/cascade/info                单条配置
 * GET  /api/v1/cascade/channellist         级联通道列表
 * POST /api/v1/cascade/save                保存配置
 * POST /api/v1/cascade/setenable           启用/停用
 * POST /api/v1/cascade/setshareallchannel  共享全部通道
 * POST /api/v1/cascade/savechannels        保存共享通道
 * POST /api/v1/cascade/removechannels      移除共享通道
 * POST /api/v1/cascade/sharenode           共享节点
 * POST /api/v1/cascade/pushcatalog         推送全部共享通道目录
 * POST /api/v1/cascade/pushchannelcatalog  推送单条通道目录
 * POST /api/v1/cascade/remove              删除配置
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cascade")
@RequiredArgsConstructor
public class CascadeController {

    private final CascadeService cascadeService;
    /** 上行链路：注册/保活/目录推送的真正执行者。 */
    private final CascadeLinkService cascadeLinkService;
    private final DeviceService deviceService;
    private final Gb28181Properties props;
    /** 只探测「有没有快照」，不现抓 —— 列表里几百条通道逐条拉流截图会打爆设备。 */
    private final io.github.rurulalanana.starter.service.SnapService snapService;

    /**
     * 保存请求体。
     *
     * <p><b>字段名为什么是 PascalCase</b>：青瓜 GBS 的 {@code /cascade/save}
     * 契约用大写开头（{@code Serial} / {@code Host} / {@code Port}），
     * 前端 {@code CascadeView.vue} 也按该契约提交，这里保持一致。
     *
     * <p><b>为什么必须加 {@code @JsonNaming}</b>：Jackson 的属性名是从
     * <b>getter</b> 推导的，不是字段名 —— {@code getSerial()} 推导出的属性名是
     * {@code serial}（首字母小写）。只把字段写成 {@code Serial} 完全无效，
     * 前端提交的 {@code {"Serial":"..."}} 会被静默丢弃，后端接着报
     * 「上级平台编号（Serial）不能为空」——调用方明明传了值却提示没传。
     * 这里用 {@code UpperCamelCaseStrategy} 把规范名统一成 PascalCase，
     * 再靠每个字段上的 {@code @JsonAlias} 兼容 camelCase / 全小写写法。
     */
    @Data
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public static class CascadeSaveRequest {
        @JsonAlias({"id"})
        private String ID;
        @JsonAlias({"enable"})
        private Boolean Enable;
        @JsonAlias({"name"})
        private String Name;
        @JsonAlias({"serial"})
        private String Serial;
        @JsonAlias({"realm"})
        private String Realm;
        @JsonAlias({"host"})
        private String Host;
        @JsonAlias({"port"})
        private Integer Port;
        @JsonAlias({"localSerial"})
        private String LocalSerial;
        @JsonAlias({"localHost"})
        private String LocalHost;
        @JsonAlias({"localPort"})
        private Integer LocalPort;
        @JsonAlias({"username"})
        private String Username;
        @JsonAlias({"password"})
        private String Password;
        @JsonAlias({"keepaliveMaxCount"})
        private Integer KeepaliveMaxCount;
        @JsonAlias({"keepaliveInterval"})
        private Integer KeepaliveInterval;
        @JsonAlias({"registerInterval"})
        private Integer RegisterInterval;
        @JsonAlias({"registerTimeout"})
        private Integer RegisterTimeout;
        @JsonAlias({"loadLimit"})
        private Integer LoadLimit;
        @JsonAlias({"catalogGroupSize"})
        private Integer CatalogGroupSize;
        @JsonAlias({"commandTransport"})
        private String CommandTransport;
        @JsonAlias({"charset"})
        private String Charset;
        @JsonAlias({"allowControl"})
        private Boolean AllowControl;
        @JsonAlias({"shareRecord"})
        private Boolean ShareRecord;
        @JsonAlias({"shareAllChannel"})
        private Boolean ShareAllChannel;
        @JsonAlias({"streamKeepalive"})
        private Boolean StreamKeepalive;
        @JsonAlias({"streamReader"})
        private Boolean StreamReader;
        @JsonAlias({"bindLocalIP"})
        private Boolean BindLocalIP;
        @JsonAlias({"controlPriority"})
        private Integer ControlPriority;
        @JsonAlias({"mediaTransport"})
        private String MediaTransport;
    }

    /** 级联配置列表。 */
    @GetMapping("/list")
    public ApiResponse<CascadeListVo> list(
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean enable,
            @RequestParam(required = false) Boolean online) {
        StpUtil.checkLogin();
        List<GbCascade> all = cascadeService.list(q, enable, online);
        List<CascadeVo> list = new ArrayList<>();
        for (GbCascade c : all) {
            list.add(cascadeView(c));
        }
        CascadeListVo vo = new CascadeListVo();
        vo.setCasecadeCount(list.size());
        vo.setCasecadeList(PageQuery.slice(list, start, limit));
        return ApiResponse.ok(vo);
    }

    /** 单条级联配置。 */
    @GetMapping("/info")
    public ApiResponse<CascadeVo> info(@RequestParam String id) {
        StpUtil.checkLogin();
        GbCascade cascade = cascadeService.find(id);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + id);
        }
        return ApiResponse.ok(cascadeView(cascade));
    }

    /** 级联状态记录。 */
    @GetMapping("/statuslog")
    public ApiResponse<StatusLogListVo> statusLog(
            @RequestParam String id,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit) {
        StpUtil.checkLogin();
        // 状态变更记录统一走 gb_status_log，按上级平台编号过滤
        GbCascade cascade = cascadeService.resolveCascade(id);
        var logs = deviceService.statusLogs(cascade.getParentId(), null, starttime, endtime);
        List<StatusLogVo> list = new ArrayList<>();
        for (var l : logs) {
            StatusLogVo vo = new StatusLogVo();
            vo.setId(String.valueOf(l.getId()));
            vo.setSerial(cascade.getCascadeId());
            vo.setCode(l.getDeviceId());
            vo.setStatus(l.getNewStatus());
            vo.setDescription(l.getDescription());
            vo.setCreatedAt(TimeFormats.toDisplay(l.getCreateTime()));
            list.add(vo);
        }
        StatusLogListVo vo = new StatusLogListVo();
        vo.setLogCount(list.size());
        vo.setLogReserveDays(30);
        vo.setLogList(list);
        return ApiResponse.ok(vo);
    }

    /**
     * 级联通道列表（共享通道选择弹窗的数据源）。
     *
     * <p>参数对齐上级平台（LiveGBS）的「选择通道」弹窗：
     * 关键字搜索、通道类型筛选、只看已选、在线筛选、排序、分页。
     *
     * @param channelType {@code all} / {@code video} / {@code audio} / {@code alarm} / {@code other}
     * @param sort        排序字段：{@code deviceId} / {@code channelId} / {@code customId} / {@code name}
     * @param order       {@code asc} / {@code desc}
     */
    @GetMapping("/channellist")
    public ApiResponse<CascadeChannelListVo> channelList(
            @RequestParam String id,
            @RequestParam(value = "channel_type", defaultValue = "all") String channelType,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean online,
            @RequestParam(defaultValue = "false") Boolean related,
            @RequestParam(defaultValue = "deviceId") String sort,
            @RequestParam(defaultValue = "asc") String order) {
        StpUtil.checkLogin();
        GbCascade cascade = cascadeService.resolveCascade(id);
        // 用「设备编码:通道编码」集合：通道编码只在单设备内唯一，
        // 只用通道编码会把十几台设备的同号通道全标成已共享
        java.util.Set<String> sharedKeys = cascadeService.sharedChannelKeys(cascade.getCascadeId());
        List<GbChannel> all = deviceService.allChannels();

        // 设备在线状态按设备编码缓存：同一个下级平台下几百路通道共用一个状态，
        // 逐条查库会变成 N 次查询（N = 通道总数），这里一次批量取。
        java.util.Map<String, Boolean> deviceOnline = deviceOnlineMap(all);

        String type = channelType == null ? "all" : channelType.trim().toLowerCase();
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();

        List<CascadeChannelVo> list = new ArrayList<>();
        int sharedCount = 0;
        for (GbChannel c : all) {
            boolean isShared = Boolean.TRUE.equals(cascade.getShareAllChannel())
                    || sharedKeys.contains(CascadeService.channelKey(c.getDeviceId(), c.getChannelId()));
            if (isShared) {
                sharedCount++;
            }
            if (Boolean.TRUE.equals(related) && !isShared) {
                continue;
            }
            CascadeChannelVo row = toCascadeChannel(c,
                    isShared ? cascade.getCascadeId() : null, deviceOnline);
            boolean onlineNow = Boolean.TRUE.equals(row.getOnline());
            if (online != null && !online.equals(onlineNow)) {
                continue;
            }
            if (!"all".equals(type) && !type.isEmpty()
                    && !type.equals(row.getChannelType())) {
                continue;
            }
            if (needle != null && !matchesKeyword(row, needle)) {
                continue;
            }
            list.add(row);
        }

        sortChannels(list, sort, order);

        CascadeChannelListVo vo = new CascadeChannelListVo();
        vo.setChannelCount(list.size());
        // 「全部共享」时实际共享数就是全部通道数，不是 gb_cascade_channel 里的记录数
        // （那个开关会让显式列表失效，界面上显示 0 路会被误读成「什么都没共享」）
        vo.setChannelRelateCount(Boolean.TRUE.equals(cascade.getShareAllChannel())
                ? list.size() : sharedCount);
        vo.setShareAllChannel(cascade.getShareAllChannel());
        vo.setChannelList(PageQuery.slice(list, start, limit));
        return ApiResponse.ok(vo);
    }

    /**
     * 组装一行级联通道视图。
     *
     * @param cascadeId    已共享时传级联 ID，未共享传 {@code null}
     * @param onlineByDev  设备编码 → 是否在线（本批次批量查出）
     */
    private CascadeChannelVo toCascadeChannel(GbChannel c, String cascadeId,
                                              java.util.Map<String, Boolean> onlineByDev) {
        CascadeChannelVo row = new CascadeChannelVo();
        row.setCascadeId(cascadeId);
        row.setId(c.getChannelId());
        row.setDeviceId(c.getDeviceId());
        row.setName(c.getName());
        row.setCustomName(c.getCustomName());
        row.setCustomId(c.getCustomId());
        row.setDeviceOnline(onlineByDev.get(c.getDeviceId()));
        // 通道可用 = 设备在线 && 通道自身 Status=ON。
        // 只看 status 会误报：设备掉线后它上报的通道 status 仍是最后一次的 ON。
        row.setOnline(Boolean.TRUE.equals(row.getDeviceOnline()) && c.isOnline());
        row.setChannel(c.getChannelNo());
        String typeCode = typeCodeOf(c.getChannelId());
        row.setTypeCode(typeCode);
        row.setChannelType(channelTypeOf(typeCode));
        row.setManufacturer(c.getManufacturer());
        row.setModel(c.getModel());
        row.setCivilCode(c.getCivilCode());
        row.setAddress(c.getAddress());
        row.setParentId(c.getParentId());
        row.setSecrecy(c.getSecrecy());
        row.setRegisterWay(c.getRegisterWay());
        row.setStatus(c.getStatus());
        row.setLongitude(c.getLongitude());
        row.setLatitude(c.getLatitude());
        row.setPtzType(c.getPtzType());
        row.setOndemand(c.getOnDemand());
        row.setAudioEnable(c.getAudio());
        if (snapService.exists(c.getDeviceId(), c.getChannelId())) {
            row.setSnapshot("/api/v1/cloudrecord/snap/" + c.getDeviceId()
                    + "/" + c.getChannelId() + ".jpg");
        }
        return row;
    }

    /** 一次性取出这批通道所属设备的在线状态。 */
    private java.util.Map<String, Boolean> deviceOnlineMap(List<GbChannel> channels) {
        java.util.Map<String, Boolean> map = new java.util.HashMap<>();
        for (GbChannel c : channels) {
            String dev = c.getDeviceId();
            if (dev == null || map.containsKey(dev)) {
                continue;
            }
            map.put(dev, deviceService.deviceOnline(dev));
        }
        return map;
    }

    /** 关键字是否命中（名称 / 自定义名 / 通道编码 / 自定义编码 / 设备编码 / 厂家）。 */
    private static boolean matchesKeyword(CascadeChannelVo row, String needle) {
        return contains(row.getName(), needle)
                || contains(row.getCustomName(), needle)
                || contains(row.getId(), needle)
                || contains(row.getCustomId(), needle)
                || contains(row.getDeviceId(), needle)
                || contains(row.getManufacturer(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    /**
     * 从 20 位国标编码取类型编码（第 11~13 位，GB/T 28181 附录 D）。
     *
     * @return 如 {@code 131}；编码不足 13 位或非数字返回 {@code null}
     */
    private static String typeCodeOf(String channelId) {
        if (channelId == null || channelId.length() < 13) {
            return null;
        }
        String sub = channelId.substring(10, 13);
        for (int i = 0; i < sub.length(); i++) {
            if (!Character.isDigit(sub.charAt(i))) {
                return null;
            }
        }
        return sub;
    }

    /**
     * 类型编码 → 大类。
     *
     * <p>归并成四类而不是照搬国标 20 多种细类：现场筛选的真实需求就是
     * 「只看视频」「只看报警」，列 20 个选项反而没人用（上级平台同样只分三类）。
     *
     * <p>编码含义（GB/T 28181—2016 附录 D）：
     * 131 摄像机 / 132 网络摄像机 / 133 网络硬盘录像机 → 视频；
     * 134 音频输入 / 135 音频输出 → 音频；
     * 137 报警输入 / 138 报警输出 / 139 报警控制器 → 报警。
     */
    private static String channelTypeOf(String typeCode) {
        if (typeCode == null) {
            return "other";
        }
        switch (typeCode) {
            case "131":
            case "132":
            case "133":
                return "video";
            case "134":
            case "135":
                return "audio";
            case "137":
            case "138":
            case "139":
                return "alarm";
            default:
                return "other";
        }
    }

    /**
     * 按指定字段排序。
     *
     * <p>与上级平台一致，默认按<b>设备编码</b>升序：同一个下级平台的通道聚在一起，
     * 找「某台设备下的通道」比按通道编码散排直观得多。
     */
    private static void sortChannels(List<CascadeChannelVo> list, String sort, String order) {
        java.util.Comparator<CascadeChannelVo> cmp;
        String key = sort == null ? "deviceId" : sort.trim();
        switch (key) {
            case "channelId":
                cmp = java.util.Comparator.comparing(
                        r -> nz(r.getId()), java.util.Comparator.naturalOrder());
                break;
            case "customId":
                // 自定义编号可能为空：空值统一排最后，避免它们挤在最前面挡住真实数据
                cmp = java.util.Comparator.comparing(
                        r -> nz(r.getCustomId()), java.util.Comparator.naturalOrder());
                break;
            case "name":
                cmp = java.util.Comparator.comparing(
                        r -> nz(r.getCustomName() != null ? r.getCustomName() : r.getName()),
                        java.util.Comparator.naturalOrder());
                break;
            default:
                // 设备编码相同时用通道编码做次级排序，否则同一设备下顺序随机
                cmp = java.util.Comparator
                        .comparing((CascadeChannelVo r) -> nz(r.getDeviceId()))
                        .thenComparing(r -> nz(r.getId()));
        }
        if ("desc".equalsIgnoreCase(order)) {
            cmp = cmp.reversed();
        }
        list.sort(cmp);
    }

    private static String nz(String s) {
        return s == null ? "\uFFFF" : s;
    }

    /** 保存级联配置。 */
    @PostMapping("/save")
    public ApiResponse<IdVo> save(@RequestBody CascadeSaveRequest req) {
        StpUtil.checkLogin();
        GbCascade cascade = new GbCascade();
        cascade.setCascadeId(req.getID());
        cascade.setName(req.getName());
        cascade.setParentId(req.getSerial());
        cascade.setParentIp(req.getHost());
        cascade.setParentPort(req.getPort());
        cascade.setParentDomain(req.getRealm());
        cascade.setUsername(req.getUsername());
        cascade.setPassword(req.getPassword());
        cascade.setParentProtocol(req.getCommandTransport());
        // 本级编号 / 地址缺省取平台配置
        cascade.setLocalId(req.getLocalSerial() == null || req.getLocalSerial().isBlank()
                ? props.getPlatformId() : req.getLocalSerial());
        cascade.setLocalIp(req.getLocalHost() == null || req.getLocalHost().isBlank()
                ? props.getSipAdvertisedIp() : req.getLocalHost());
        cascade.setLocalPort(req.getLocalPort() == null ? props.getSipPort() : req.getLocalPort());
        cascade.setKeepaliveMaxCount(req.getKeepaliveMaxCount());
        cascade.setKeepaliveInterval(req.getKeepaliveInterval());
        cascade.setRegisterInterval(req.getRegisterInterval());
        cascade.setRegisterTimeout(req.getRegisterTimeout());
        cascade.setLoadLimit(req.getLoadLimit());
        cascade.setCatalogGroupSize(req.getCatalogGroupSize());
        cascade.setCharset(req.getCharset());
        cascade.setAllowControl(req.getAllowControl());
        cascade.setShareAllChannel(req.getShareAllChannel());
        cascade.setStreamKeepalive(req.getStreamKeepalive());
        cascade.setControlPriority(req.getControlPriority());
        cascade.setMediaTransport(normalizeTransport(req.getMediaTransport()));
        cascade.setEnabled(req.getEnable());
        String cascadeId = cascadeService.save(cascade);
        /*
         * 保存后立即重建链路。
         *
         * <p>地址、端口、密码、本级编号任何一项改了，旧链路都是错的，
         * 而它仍在按旧配置发心跳 —— 表现是「界面上改对了但还是离线」，
         * 运维会反复重保存。这里直接重启一遍，让配置改动立刻反映到信令上。
         *
         * <p>只重建、不报错：上级不可达时保存仍然应该成功（配置是对的），
         * 失败原因会写进 remark 供界面展示。
         */
        cascadeLinkService.restart(cascadeId);
        // 上级编码可能变了（新建或改地址），必须刷新判定表，
        // 否则它点播时会被当成普通设备 INVITE，永远拿不到流
        cascadeLinkService.refreshParentRegistry();
        return ApiResponse.ok(IdVo.of(cascadeId));
    }

    /** 启用 / 停用。 */
    @PostMapping("/setenable")
    public ApiResponse<Void> setEnable(@RequestParam String id,
                                       @RequestParam(defaultValue = "false") Boolean enable) {
        StpUtil.checkLogin();
        boolean on = Boolean.TRUE.equals(enable);
        cascadeService.setEnable(id, on);
        // 启用 → 立即注册；停用 → 向上级注销。
        // 停用时不发 Expires:0，上级会一直认为本级在线直到注册有效期自然过期，
        // 期间它点播本级通道会全部失败，且上级侧看不到任何原因。
        if (on) {
            cascadeLinkService.enable(id);
        } else {
            cascadeLinkService.disable(id);
        }
        // 只登记已启用的上级：停用的级联不应能点播本级通道
        cascadeLinkService.refreshParentRegistry();
        return ApiResponse.ok();
    }

    /** 共享全部通道。 */
    @PostMapping("/setshareallchannel")
    public ApiResponse<Void> setShareAllChannel(
            @RequestParam String id,
            @RequestParam(defaultValue = "false") Boolean shareallchannel,
            @RequestParam(defaultValue = "false") Boolean cleanrelated) {
        StpUtil.checkLogin();
        cascadeService.setShareAllChannel(id, Boolean.TRUE.equals(shareallchannel),
                Boolean.TRUE.equals(cleanrelated));
        return ApiResponse.ok();
    }

    /**
     * 保存共享通道（<b>全量覆盖</b>）。
     *
     * <p>传空数组表示取消全部共享 —— 所以 {@code channels[]} 必须是
     * {@code required = false}：前端「清空后保存」时不会带这个参数，
     * 若声明为必填，这个合法操作会被 400 挡住。
     */
    @PostMapping("/savechannels")
    public ApiResponse<DeletedVo> saveChannels(
            @RequestParam String id,
            @RequestParam(value = "channels[]", required = false) List<String> channels) {
        StpUtil.checkLogin();
        int count = cascadeService.saveChannels(id, channels == null ? List.of() : channels);
        return ApiResponse.ok(DeletedVo.ofSaved(count));
    }

    /** 移除共享通道。 */
    @PostMapping("/removechannels")
    public ApiResponse<DeletedVo> removeChannels(@RequestParam String id,
                                                 @RequestParam("channels[]") List<String> channels) {
        StpUtil.checkLogin();
        int count = cascadeService.removeChannels(id, channels);
        return ApiResponse.ok(DeletedVo.ofRemoved(count));
    }

    /** 共享 / 取消共享节点。 */
    @PostMapping("/sharenode")
    public ApiResponse<Void> shareNode(@RequestParam String serial,
                                       @RequestParam String code,
                                       @RequestParam(value = "cascade_id") String cascadeId,
                                       @RequestParam(defaultValue = "true") Boolean share) {
        StpUtil.checkLogin();
        // code 为空时说明调用方把通道编码传在 serial 上（兼容老调用）
        String channelId = code == null || code.isBlank() ? serial : code;
        String deviceId = code == null || code.isBlank() ? null : serial;
        cascadeService.shareNode(cascadeId, deviceId, channelId,
                Boolean.TRUE.equals(share));
        return ApiResponse.ok();
    }

    /** 推送全部共享通道目录。 */
    @PostMapping("/pushcatalog")
    public ApiResponse<DeletedVo> pushCatalog(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String serial) {
        StpUtil.checkLogin();
        String key = id != null && !id.isBlank() ? id : serial;
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("需提供 id 或 serial");
        }
        int pushed = cascadeLinkService.pushCatalog(key);
        return ApiResponse.ok(DeletedVo.ofPushed(pushed));
    }

    /** 推送单条共享通道目录。 */
    @PostMapping("/pushchannelcatalog")
    public ApiResponse<Void> pushChannelCatalog(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String serial,
            @RequestParam String code,
            @RequestParam(defaultValue = "UPDATE") String event) {
        StpUtil.checkLogin();
        String key = id != null && !id.isBlank() ? id : serial;
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("需提供 id 或 serial");
        }
        cascadeLinkService.pushChannelCatalog(key, code, event);
        return ApiResponse.ok();
    }

    /** 删除级联配置。 */
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String id) {
        StpUtil.checkLogin();
        // 先注销再删：先删就查不到上级地址，Expires:0 发不出去，
        // 上级会一直认为本级在线直到自然过期
        cascadeLinkService.forget(id);
        cascadeService.remove(id);
        cascadeLinkService.refreshParentRegistry();
        return ApiResponse.ok();
    }

    // ==================== 内部 ====================

    /**
     * 归一化「上行推流传输方式」入参。
     *
     * <p>非法值一律落到 {@code auto}（跟随上级 SDP），避免因为前端传了个
     * 不认识的字符串就把推流卡死。
     */
    private static String normalizeTransport(String v) {
        if (v == null) {
            return "auto";
        }
        String s = v.trim().toLowerCase();
        if ("tcp".equals(s) || "udp".equals(s) || "auto".equals(s)) {
            return s;
        }
        return "auto";
    }

    private CascadeVo cascadeView(GbCascade c) {        CascadeVo vo = new CascadeVo();
        vo.setId(c.getCascadeId());
        vo.setName(c.getName());
        vo.setSerial(c.getParentId());
        vo.setRealm(c.getParentDomain());
        vo.setHost(c.getParentIp());
        vo.setPort(c.getParentPort());
        vo.setLocalSerial(c.getLocalId());
        vo.setLocalHost(c.getLocalIp());
        vo.setLocalPort(c.getLocalPort());
        vo.setUsername(c.getUsername());
        vo.setPassword(c.getPassword());
        vo.setKeepaliveMaxCount(c.getKeepaliveMaxCount());
        vo.setKeepaliveInterval(c.getKeepaliveInterval());
        vo.setRegisterInterval(c.getRegisterInterval());
        vo.setRegisterTimeout(c.getRegisterTimeout());
        vo.setControlPriority(c.getControlPriority());
        vo.setLoadLimit(c.getLoadLimit());
        vo.setCatalogGroupSize(c.getCatalogGroupSize());
        vo.setCommandTransport(c.getParentProtocol());
        vo.setCharset(c.getCharset());
        vo.setOnline(Boolean.TRUE.equals(c.getOnline()));
        vo.setAllowControl(c.getAllowControl());
        vo.setShareAllChannel(c.getShareAllChannel());
        vo.setStreamKeepalive(c.getStreamKeepalive());
        vo.setEnable(c.getEnabled());
        vo.setRemark(c.getRemark());
        vo.setMediaTransport(c.getMediaTransport());
        vo.setCreatedAt(TimeFormats.toDisplay(c.getCreateTime()));
        vo.setUpdatedAt(TimeFormats.toDisplay(c.getUpdateTime()));
        return vo;
    }
}
