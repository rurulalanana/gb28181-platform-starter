package io.github.rurulalanana.starter.web;

import io.github.rurulalanana.starter.dto.DeviceRef;
import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Numbers;
import io.github.rurulalanana.starter.common.StreamIndex;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.common.TreeBuilder;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.dto.ChannelListQuery;
import io.github.rurulalanana.starter.dto.DeviceListQuery;
import io.github.rurulalanana.starter.dto.PageQuery;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbDeviceGroup;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.mapper.GbDeviceGroupMapper;
import io.github.rurulalanana.starter.service.ChannelRecordPolicy;
import io.github.rurulalanana.starter.service.ControlService;
import io.github.rurulalanana.starter.service.DahuaCapabilityService;
import io.github.rurulalanana.starter.service.DeviceCollectService;
import io.github.rurulalanana.starter.service.DeviceProvisionService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.PresetService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.ChannelListVo;
import io.github.rurulalanana.starter.vo.ChannelNavigateVo;
import io.github.rurulalanana.starter.vo.ChannelVo;
import io.github.rurulalanana.starter.vo.DeviceCapabilitiesVo;
import io.github.rurulalanana.starter.vo.DeviceListVo;
import io.github.rurulalanana.starter.vo.DeviceVo;
import io.github.rurulalanana.starter.vo.DiscoveredDeviceVo;
import io.github.rurulalanana.starter.vo.FetchResultVo;
import io.github.rurulalanana.starter.vo.OnlineStatsVo;
import io.github.rurulalanana.starter.vo.PositionLogListVo;
import io.github.rurulalanana.starter.vo.PositionLogVo;
import io.github.rurulalanana.starter.vo.PresetListVo;
import io.github.rurulalanana.starter.vo.SetPasswordVo;
import io.github.rurulalanana.starter.vo.StatusLogListVo;
import io.github.rurulalanana.starter.vo.StatusLogVo;
import io.github.rurulalanana.starter.vo.StreamLogVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import io.github.rurulalanana.starter.vo.TreeNodeVo;
import io.github.rurulalanana.starter.vo.TreeVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备信息接口。主要路由：
 * <pre>
 * GET  /api/v1/device/list / info / channellist / channeltree / grouptree
 * GET  /api/v1/device/channelinfo / onlinestats / statuslog / streamlog / positionlog
 * POST /api/v1/device/fetchcatalog / fetchinfo / fetchstatus / fetchconfig / fetchpreset
 * POST /api/v1/device/setname / setchannelname / setchannelcloudrecord / setinfo ...
 * </pre>
 */
@Slf4j
@RestController
@Tag(name = "设备与通道", description = "设备/通道查询、采集、维护")
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final RecordService recordService;
    private final StreamService streamService;
    private final ControlService controlService;
    private final DeviceCollectService collectService;
    private final PresetService presetService;
    private final ChannelRecordPolicy recordPolicy;
    private final GbDeviceGroupMapper groupMapper;
    private final Gb28181Properties props;
    private final DeviceProvisionService provisionService;
    /** SDK 直连设备的能力入口（预置位同步读、局域网发现等）。 */
    private final DahuaCapabilityService dahuaCapability;

    // ==================== 设备 ====================

    /**
     * 纳管一台 SDK 直连设备（大华 NetSDK）。国标设备由 REGISTER 推上来，平台从不主动建；
     * SDK 设备不会注册，必须录入地址与凭据。录入后就是普通 {@code gb_device} 行（{@code source=dahua}），
     * 设备/通道/点播/云台/录像接口照旧可用，前端无需区分类型。
     */
    @Operation(summary = "纳管一台 SDK 直连设备（大华 NetSDK）")
    @PostMapping("/adddahua")
    public ApiResponse<DeviceVo> addDahua(@RequestParam String serial,
                                          @RequestParam String host,
                                          @RequestParam(defaultValue = "37777") Integer port,
                                          @RequestParam String username,
                                          @RequestParam String password,
                                          @RequestParam(required = false) String name) {
        StpUtil.checkLogin();
        GbDevice d = provisionService.addDahua(serial, host,
                port == null ? 37777 : port, username, password, name);
        return ApiResponse.ok(deviceView(d));
    }

    /**
     * 局域网搜索大华设备（{@code CLIENT_StartSearchDevices}）。
     *
     * <p><b>只在同一广播域有效</b>：跨三层网络收不到应答，这是协议决定的，
     * 不是平台缺陷。跨网段请用「按 IP 录入」或让设备主动注册。
     *
     * <p>返回结果不落库 —— 发现到设备后还需要用户填账号密码，
     * 由前端调 {@code /adddahua} 完成纳管。
     *
     * @param localIp 本机网卡地址；不填由 SDK 自选（多网卡机器建议指定）
     * @param seconds 收集时长，默认 5 秒（太短会漏掉响应慢的设备）
     */
    @Operation(summary = "局域网搜索大华设备")
    @GetMapping("/discover")
    public ApiResponse<java.util.List<DiscoveredDeviceVo>> discover(
            @RequestParam(required = false) String localIp,
            @RequestParam(defaultValue = "5") Integer seconds) {
        StpUtil.checkLogin();
        int wait = seconds == null ? 5 : Math.max(1, Math.min(30, seconds));
        var found = dahuaCapability.discoverLan(localIp, wait * 1000L);
        java.util.List<DiscoveredDeviceVo> list = new java.util.ArrayList<>(found.size());
        for (var d : found) {
            DiscoveredDeviceVo vo = new DiscoveredDeviceVo();
            vo.setSerial(d.serial());
            vo.setIp(d.ip());
            vo.setSdkPort(d.sdkPort());
            vo.setHttpPort(d.httpPort());
            vo.setModel(d.model());
            vo.setFirmware(d.firmware());
            vo.setChannels(d.channels());
            vo.setManaged(deviceService.findDevice(d.serial()) != null);
            list.add(vo);
        }
        log.info("局域网发现 {} 台大华设备（{} 秒，本机网卡 {}）", list.size(), wait,
                localIp == null ? "自动" : localIp);
        return ApiResponse.ok(list);
    }

    /**
     * 探测 SDK 直连设备并同步通道目录。国标走 {@link #fetchCatalog}（下发 Catalog 等应答），
     * SDK 走本接口（平台主动去问，同步返回）。
     */
    @Operation(summary = "探测 SDK 直连设备并同步通道目录")
    @PostMapping("/refreshsdk")
    public ApiResponse<FetchResultVo> refreshSdk(@RequestParam String serial) {
        StpUtil.checkLogin();
        int channels = provisionService.refreshCatalog(serial);
        return ApiResponse.ok(FetchResultVo.ok(serial,
                "已同步 " + channels + " 个通道"));
    }

    @Operation(summary = "设备列表")
    @GetMapping("/list")
    public ApiResponse<DeviceListVo> list(DeviceListQuery query) {
        StpUtil.checkLogin();
        List<GbDevice> all = deviceService.allDevices();
        List<DeviceVo> filtered = new ArrayList<>();
        for (GbDevice d : all) {
            if (query.getOnline() != null
                    && !query.getOnline().equals(Boolean.TRUE.equals(d.getOnline()))) {
                continue;
            }
            if (!query.matches(d.getDeviceId(), d.getName(), d.getCustomName(),
                    d.getManufacturer(), d.getModel())) {
                continue;
            }
            filtered.add(deviceView(d));
        }
        DeviceListVo vo = new DeviceListVo();
        vo.setDeviceCount(filtered.size());
        vo.setDeviceList(query.slice(filtered));
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "单条设备信息")
    @GetMapping("/info")
    public ApiResponse<DeviceVo> info(@RequestParam String serial) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        return ApiResponse.ok(deviceView(device));
    }

    /**
     * 该设备支持哪些控制能力。
     *
     * <p>前端在渲染云台、预置位、重启等按钮前先取一次，把不支持的按钮置灰并显示原因 ——
     * 而不是让用户点完再收一个 400「该设备不支持」。
     *
     * <p>返回值同时包含<b>不支持的项与原因</b>：只返回支持列表会让用户看到按钮凭空消失，
     * 以为是自己找错了地方。
     *
     * <p>这是统一控制契约带来的附加收益：改造前能力信息散在 31 个 {@code if} 分支里，
     * 前端无从得知，只能「全都显示、点了再看报错」。
     */
    @Operation(summary = "设备控制能力清单")
    @GetMapping("/capabilities")
    public ApiResponse<DeviceCapabilitiesVo> capabilities(@RequestParam String serial) {
        StpUtil.checkLogin();
        return ApiResponse.ok(controlService.capabilities(serial));
    }

    @Operation(summary = "在线统计")
    @GetMapping("/onlinestats")
    public ApiResponse<OnlineStatsVo> onlineStats() {
        StpUtil.checkLogin();
        long[] deviceStats = deviceService.deviceStats();
        long[] channelStats = deviceService.channelStats();
        OnlineStatsVo vo = new OnlineStatsVo();
        vo.setDeviceTotal(deviceStats[0]);
        vo.setDeviceOnline(deviceStats[1]);
        vo.setChannelTotal(channelStats[0]);
        vo.setChannelOnline(channelStats[1]);
        return ApiResponse.ok(vo);
    }

    // ==================== 通道 ====================

    @Operation(summary = "通道列表")
    @GetMapping("/channellist")
    public ApiResponse<ChannelListVo> channelList(ChannelListQuery query) {
        StpUtil.checkLogin();
        List<GbChannel> all = deviceService.allChannels();
        List<ChannelVo> filtered = new ArrayList<>();
        for (GbChannel c : all) {
            if (!matchChannel(c, query)) {
                continue;
            }
            filtered.add(channelView(c));
        }
        ChannelListVo vo = new ChannelListVo();
        vo.setChannelCount(filtered.size());
        vo.setChannelList(query.slice(filtered));
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "单条通道信息")
    @GetMapping("/channelinfo")
    public ApiResponse<ChannelVo> channelInfo(@RequestParam String serial,
                                              @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        GbChannel channel = deviceService.resolveChannel(serial, code);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + (code == null ? serial : code));
        }
        return ApiResponse.ok(channelView(channel));
    }

    /** serial 为空时返回顶级设备节点；subfetch=false 只返回一层。 */
    @Operation(summary = "通道树")
    @GetMapping("/channeltree")
    public ApiResponse<TreeVo> channelTree(
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String pcode,
            @RequestParam(defaultValue = "false") boolean subfetch,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "500") int limit) {
        StpUtil.checkLogin();
        List<TreeNodeVo> nodes = new ArrayList<>();
        if (serial == null || serial.isBlank()) {
            // 顶级：设备节点
            for (GbDevice d : deviceService.allDevices()) {
                List<GbChannel> channels = deviceService.channelsOf(d.getDeviceId());
                List<TreeNodeVo> children = subfetch
                        ? TreeBuilder.build(d.getDeviceId(), channels) : null;
                nodes.add(TreeBuilder.deviceNode(d.getDeviceId(), d.displayName(),
                        Boolean.TRUE.equals(d.getOnline()) ? "ON" : "OFF",
                        channels.size(),
                        channels.stream().filter(GbChannel::isOnline).count(),
                        children));
            }
        } else {
            List<GbChannel> channels = deviceService.channelsOf(serial);
            nodes = TreeBuilder.build(pcode == null || pcode.isBlank() ? serial : pcode, channels);
            if (!subfetch) {
                // 立即加载：剥掉 children，只保留一层
                for (TreeNodeVo n : nodes) {
                    n.setChildren(null);
                }
            }
        }
        TreeVo vo = new TreeVo();
        vo.setNodeList(limit > 0 ? PageQuery.slice(nodes, start, limit) : nodes);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "分组树")
    @GetMapping("/grouptree")
    public ApiResponse<TreeVo> groupTree(
            @RequestParam(required = false) String pcode,
            @RequestParam(defaultValue = "false") boolean subfetch) {
        StpUtil.checkLogin();
        List<GbDeviceGroup> groups = groupMapper.selectList(Wrappers.<GbDeviceGroup>lambdaQuery()
                .orderByAsc(GbDeviceGroup::getSortNo));
        Map<String, List<GbDeviceGroup>> byParent = new LinkedHashMap<>();
        for (GbDeviceGroup g : groups) {
            byParent.computeIfAbsent(g.getParentCode(), k -> new ArrayList<>()).add(g);
        }
        TreeVo vo = new TreeVo();
        vo.setNodeList(buildGroupLevel(
                pcode == null || pcode.isBlank() ? null : pcode, byParent, subfetch));
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "位置记录")
    @GetMapping("/positionlog")
    public ApiResponse<PositionLogListVo> positionLog(
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit) {
        StpUtil.checkLogin();
        var logs = deviceService.positionLogs(serial, code, starttime, endtime);
        List<PositionLogVo> list = new ArrayList<>();
        for (var l : logs) {
            PositionLogVo vo = new PositionLogVo();
            vo.setDeviceId(l.getDeviceId());
            vo.setChannelId(l.getChannelId());
            vo.setLongitude(l.getLongitude());
            vo.setLatitude(l.getLatitude());
            vo.setAltitude(l.getAltitude());
            vo.setSpeed(l.getSpeed());
            vo.setDirection(l.getDirection());
            vo.setTime(TimeFormats.toDisplay(l.getCreateTime()));
            list.add(vo);
        }
        PositionLogListVo vo = new PositionLogListVo();
        vo.setLogCount(list.size());
        vo.setLogList(limit > 0 ? PageQuery.slice(list, start, limit) : list);
        return ApiResponse.ok(vo);
    }

    // ==================== 抓取类 ====================

    @Operation(summary = "主动抓取下级通道目录")
    @PostMapping("/fetchcatalog")
    public ApiResponse<FetchResultVo> fetchCatalog(
            @RequestParam String serial,
            @RequestParam(required = false) String start_time,
            @RequestParam(required = false) String end_time,
            @RequestParam(defaultValue = "false") Boolean wait) {
        StpUtil.checkLogin();
        // 目录查询是异步的：应答通过 MESSAGE 回来，由 SipEventBridge 落库。
        // wait=true 时改为「下发 + 采集信息/状态」的完整一轮，便于一键拉全量数据
        collectService.collectOnOnline(serial, true);
        deviceService.logStatus(serial, null, null, null, "主动抓取目录", "manual");
        return ApiResponse.ok(FetchResultVo.ok(serial,
                "已下发 Catalog 查询，结果异步落库，可稍后查询 channellist"));
    }

    @Operation(summary = "抓取下级设备信息")
    @PostMapping("/fetchinfo")
    public ApiResponse<FetchResultVo> fetchInfo(@RequestParam String serial,
                                                @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryDeviceInfo(DeviceRef.channelOf(serial, code));
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    @Operation(summary = "抓取下级通道状态")
    @PostMapping("/fetchstatus")
    public ApiResponse<FetchResultVo> fetchStatus(@RequestParam String serial,
                                                  @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        controlService.queryDeviceStatus(channelId);
        return ApiResponse.ok(FetchResultVo.ok(channelId));
    }

    @Operation(summary = "抓取下级通道配置")
    @PostMapping("/fetchconfig")
    public ApiResponse<FetchResultVo> fetchConfig(@RequestParam String serial,
                                                  @RequestParam(required = false) String code,
                                                  @RequestParam("config_type") String configType) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        controlService.queryConfig(channelId, configType);
        return ApiResponse.ok(FetchResultVo.ok(channelId));
    }

    @Operation(summary = "抓取预置位")
    @PostMapping("/fetchpreset")
    public ApiResponse<FetchResultVo> fetchPreset(@RequestParam String serial,
                                                  @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        controlService.queryPresets(channelId);
        return ApiResponse.ok(FetchResultVo.ok(channelId));
    }

    /**
     * 读取通道的预置位列表。数据来自设备对 {@code PresetQuery} 的应答，必须配合
     * {@link #fetchPreset} 使用：先 POST fetchpreset 下发查询，等设备应答后再 GET 本接口取结果。
     * {@code queriedAt} 为 {@code null} 表示尚未收到应答，前端据此继续轮询或提示「设备未响应」。
     */
    @Operation(summary = "读取通道的预置位列表")
    @GetMapping("/presetlist")
    public ApiResponse<PresetListVo> presetList(@RequestParam String serial,
                                                @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        /*
         * SDK 直连设备同步读，国标设备读「设备应答后的内存快照」。
         * 两条路的 queriedAt 语义不同但界面一致：null = 还没查到。
         */
        if (dahuaCapability.isDahua(serial)) {
            return ApiResponse.ok(presetService.listBySdk(serial, channelId, channelId));
        }
        return ApiResponse.ok(presetService.list(channelId));
    }

    @Operation(summary = "抓取看守位信息")
    @PostMapping("/fetchhomeposition")
    public ApiResponse<FetchResultVo> fetchHomePosition(@RequestParam String serial,
                                                        @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryHomePosition(DeviceRef.channelOf(serial, code));
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    @Operation(summary = "抓取巡航组列表")
    @PostMapping("/fetchcruisetracklist")
    public ApiResponse<FetchResultVo> fetchCruiseList(@RequestParam String serial,
                                                      @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryCruiseTrack(DeviceRef.channelOf(serial, code), 0);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    @Operation(summary = "抓取巡航轨迹")
    @PostMapping("/fetchcruisetrack")
    public ApiResponse<FetchResultVo> fetchCruiseTrack(@RequestParam String serial,
                                                       @RequestParam(required = false) String code,
                                                       @RequestParam(defaultValue = "0") Integer number) {
        StpUtil.checkLogin();
        controlService.queryCruiseTrack(DeviceRef.channelOf(serial, code), number);
        FetchResultVo vo = FetchResultVo.ok(serial);
        vo.setNumber(number);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "抓取 PTZ 精确状态")
    @PostMapping("/fetchptzposition")
    public ApiResponse<FetchResultVo> fetchPtzPosition(@RequestParam String serial,
                                                       @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryPtzPosition(DeviceRef.channelOf(serial, code));
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    @Operation(summary = "抓取存储卡状态")
    @PostMapping("/fetchsdcardstatus")
    public ApiResponse<FetchResultVo> fetchSdCardStatus(@RequestParam String serial,
                                                        @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.querySdCardStatus(DeviceRef.channelOf(serial, code));
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    @Operation(summary = "查询通道巡航列表")
    @GetMapping("/getchannelnavigate")
    public ApiResponse<ChannelNavigateVo> getChannelNavigate(@RequestParam String serial,
                                                             @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = DeviceRef.channelOf(serial, code);
        controlService.queryCruiseTrack(channelId, 0);
        ChannelNavigateVo vo = new ChannelNavigateVo();
        vo.setDeviceId(channelId);
        vo.setSumNum(0);
        vo.setGroupList(List.of());
        return ApiResponse.ok(vo);
    }

    // ==================== 状态 / 流量记录 ====================

    @Operation(summary = "状态变更记录")
    @GetMapping("/statuslog")
    public ApiResponse<StatusLogListVo> statusLog(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit) {
        StpUtil.checkLogin();
        var logs = deviceService.statusLogs(serial, code, starttime, endtime);
        List<StatusLogVo> list = new ArrayList<>();
        for (var l : logs) {
            StatusLogVo vo = new StatusLogVo();
            vo.setId(String.valueOf(l.getId()));
            vo.setSerial(l.getDeviceId());
            vo.setCode(l.getChannelId());
            vo.setStatus(l.getNewStatus());
            vo.setDescription(l.getDescription());
            vo.setCreatedAt(TimeFormats.toDisplay(l.getCreateTime()));
            list.add(vo);
        }
        StatusLogListVo vo = new StatusLogListVo();
        vo.setLogCount(list.size());
        vo.setLogReserveDays(30);
        vo.setLogList(limit > 0 ? PageQuery.slice(list, start, limit) : list);
        return ApiResponse.ok(vo);
    }

    /**
     * 流量统计。嵌入式 ZLMediaKit 未注册 {@code /index/api/*}，无法查询流量计数；
     * 需要真实流量时应改为独立部署 MediaServer 并读取其 REST 接口。
     */
    @Operation(summary = "流量统计")
    @GetMapping("/streamlog")
    public ApiResponse<StreamLogVo> streamLog(
            @RequestParam String serial,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "today") String type) {
        StpUtil.checkLogin();
        StreamLogVo vo = new StreamLogVo();
        vo.setList(List.of());
        vo.setNote("嵌入式 ZLMediaKit 不提供流量计数接口，需独立部署 MediaServer 后接入");
        return ApiResponse.ok(vo);
    }

    // ==================== 属性编辑 ====================

    @Operation(summary = "自定义设备名称")
    @PostMapping("/setname")
    public ApiResponse<Void> setDeviceName(@RequestParam String serial,
                                           @RequestParam(value = "custom_name", required = false) String customName) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        device.setCustomName(customName);
        device.setUpdateTime(LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    @Operation(summary = "自定义通道名称")
    @PostMapping("/setchannelname")
    public ApiResponse<Void> setChannelName(@RequestParam String serial,
                                            @RequestParam String code,
                                            @RequestParam(required = false) String name,
                                            @RequestParam(value = "custom_id", required = false) String customId,
                                            @RequestParam(required = false) String description) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setCustomName(name);
        if (customId != null && !customId.isBlank()) {
            channel.setCustomId(customId);
        }
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /**
     * 自定义通道国标编号。改的是 {@code custom_id}，不动 {@code channel_id}（后者是设备实际用的编号，
     * 改了连不上设备）。{@code id} 传空表示清除自定义编号。
     * 必须按 serial + code 定位：通道编码只在单设备内唯一，多设备可能有相同编码。
     */
    @Operation(summary = "自定义通道国标编号")
    @PostMapping("/setchannelid")
    public ApiResponse<Void> setChannelId(@RequestParam String serial,
                                          @RequestParam String code,
                                          @RequestParam(required = false) String id) {
        StpUtil.checkLogin();
        // 不能用 updateChannel：updateById 会跳过 null 字段，清空就永远写不进库
        boolean ok = deviceService.setChannelCustomId(serial, code, id);
        if (!ok) {
            throw BizException.notFound("通道不存在：" + serial + ":" + code);
        }
        return ApiResponse.ok();
    }

    @Operation(summary = "自定义通道云台类型")
    @PostMapping("/setchannelptztype")
    public ApiResponse<Void> setChannelPtzType(@RequestParam String serial,
                                               @RequestParam String code,
                                               @RequestParam(value = "ptz_type", defaultValue = "0") Integer ptzType) {
        StpUtil.checkLogin();
        // 附录 A.3 只定义了 0~4：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。
        // 界面上该字段是下拉，但接口可被脚本直接调用；写进 99 这类值后
        // 前端 supportsPtz() 判定为「不支持云台」，该通道的云台入口会消失。
        if (ptzType != null && (ptzType < 0 || ptzType > 4)) {
            throw BizException.badRequest("ptz_type 只能是 0~4，收到：" + ptzType);
        }
        GbChannel channel = requireChannel(code);
        channel.setPtzType(ptzType);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    @Operation(summary = "自定义通道移动位置")
    @PostMapping("/setchannelposition")
    public ApiResponse<Void> setChannelPosition(
            @RequestParam String serial, @RequestParam String code,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double altitude,
            @RequestParam(required = false) Double speed,
            @RequestParam(required = false) Double direction) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        if (longitude != null) {
            channel.setLongitude(java.math.BigDecimal.valueOf(longitude));
        }
        if (latitude != null) {
            channel.setLatitude(java.math.BigDecimal.valueOf(latitude));
        }
        if (altitude != null) {
            channel.setAltitude(java.math.BigDecimal.valueOf(altitude));
        }
        if (speed != null) {
            channel.setSpeed(java.math.BigDecimal.valueOf(speed));
        }
        if (direction != null) {
            channel.setDirection(java.math.BigDecimal.valueOf(direction));
        }
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    @Operation(summary = "自定义通道属性")
    @PostMapping("/setchannelproperty")
    public ApiResponse<Void> setChannelProperty(@RequestParam String serial,
                                                @RequestParam String code,
                                                @RequestParam("k") String key,
                                                @RequestParam(value = "v", required = false) String value) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        switch (key == null ? "" : key) {
            case "Name" -> channel.setName(value);
            case "Status" -> channel.setStatus(value);
            case "Block" -> channel.setBlock(value);
            case "CivilCode" -> channel.setCivilCode(value);
            case "Address" -> channel.setAddress(value);
            case "Manufacturer" -> channel.setManufacturer(value);
            case "Model" -> channel.setModel(value);
            case "Firmware" -> channel.setFirmware(value);
            case "SerialNumber" -> channel.setSerialNumber(value);
            case "IPAddress" -> channel.setIpAddress(value);
            case "Port" -> channel.setPort(Numbers.intOrNull(value));
            case "Longitude" -> channel.setLongitude(Numbers.decimalOrNull(value));
            case "Latitude" -> channel.setLatitude(Numbers.decimalOrNull(value));
            default -> throw BizException.badRequest("不支持的属性名：" + key);
        }
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /**
     * 设置通道按需直播。与云端录像互斥：按需 = 没人看就停流，云端录像 = 持续有流，
     * 「有人看就录、没人看就停」只能录出残缺片段。开按需会同时关录像并归档已有片段。
     */
    @Operation(summary = "设置通道按需直播")
    @PostMapping("/setchannelondemand")
    public ApiResponse<Void> setChannelOndemand(@RequestParam String serial,
                                                @RequestParam String code,
                                                @RequestParam(defaultValue = "true") Boolean ondemand,
                                                @RequestParam(value = "check_outputs", defaultValue = "true")
                                                Boolean checkOutputs) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setOnDemand(ondemand);
        // 互斥：开按需直播必须同时关掉云端录像
        boolean turnedOffRecord = false;
        if (Boolean.TRUE.equals(ondemand) && Boolean.TRUE.equals(channel.getCloudRecord())) {
            channel.setCloudRecord(false);
            turnedOffRecord = true;
        }
        deviceService.updateChannel(channel);
        if (turnedOffRecord) {
            recordPolicy.set(code, false);
            try {
                if (recordService.isRecording(code)) {
                    recordService.stopCloudRecord(code);
                }
            } catch (Exception e) {
                log.warn("开按需直播时停止录像失败（开关已关闭，下次流上下线自会停）: channel={} {}",
                        code, e.getMessage());
            }
            log.info("通道 {} 开启按需直播，已同时关闭云端录像（两者互斥）", code);
        }
        if (Boolean.TRUE.equals(ondemand) && Boolean.TRUE.equals(checkOutputs)) {
            log.info("通道 {} 已设为按需直播，无人观看时将由 ZLM 自动停流", code);
        }
        return ApiResponse.ok();
    }

    /**
     * 设置通道云端录像。开关必须同步内存镜像：流上线回调读镜像而不是库表（ZLM 回调线程不能查库），
     * 只改库不改镜像开关永不生效。开启时若无流则主动拉流（{@code mk_recorder_start} 找不到媒体源直接失败），
     * 否则用户打开开关后什么都看不到；关闭时若在录则立即停止并归档。
     */
    @Operation(summary = "设置通道云端录像")
    @PostMapping("/setchannelcloudrecord")
    public ApiResponse<Void> setChannelCloudRecord(@RequestParam String serial,
                                                   @RequestParam String code,
                                                   @RequestParam(defaultValue = "false") Boolean cloudrecord) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setCloudRecord(cloudrecord);
        // 互斥：开云端录像必须关掉按需直播。两条路径都要兜住，
        // 否则结果取决于先开哪个（实测踩过）。
        boolean turnedOffOnDemand = false;
        if (Boolean.TRUE.equals(cloudrecord) && Boolean.TRUE.equals(channel.getOnDemand())) {
            channel.setOnDemand(false);
            turnedOffOnDemand = true;
        }
        deviceService.updateChannel(channel);
        recordPolicy.set(code, Boolean.TRUE.equals(cloudrecord));
        if (turnedOffOnDemand) {
            log.info("通道 {} 开启云端录像，已同时关闭按需直播（两者互斥，改为常开推流）", code);
        }
        try {
            if (Boolean.TRUE.equals(cloudrecord)) {
                // 用库里的 device_id 而不是入参 serial：serial 可能是平台自身编码或任意值，
                // 拿它点播会生成错误流名（rtp/<错设备>_<通道>），INVITE 失败还会留僵尸会话。
                String deviceId = channel.getDeviceId() != null
                        ? channel.getDeviceId() : serial;
                // startLive 内部会区分「可复用的活会话」与「已关闭/无流的死会话」，
                // 后者重建并发起 INVITE；已可复用时是空操作。
                streamService.startLive(deviceId, code);
                log.info("通道 {} 开启云端录像，已确保流在线（device={}，开录由流上线回调触发）",
                        code, deviceId);
            } else if (recordService.isRecording(code)) {
                recordService.stopCloudRecord(code);
                log.info("通道 {} 已关闭录像并归档", code);
            }
        } catch (Exception e) {
            // 开关已生效；开/停录失败只告警，不回滚（下一次流上下线会重试）
            log.warn("按开关即时开/停录像失败: channel={} {}", code, e.getMessage());
        }
        return ApiResponse.ok();
    }

    @Operation(summary = "设置通道音频开关")
    @PostMapping("/setchannelaudio")
    public ApiResponse<Void> setChannelAudio(@RequestParam String serial,
                                             @RequestParam String code,
                                             @RequestParam(defaultValue = "false") Boolean audio) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setAudio(audio);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    @Operation(summary = "设置通道分享开关")
    @PostMapping("/setchannelshared")
    public ApiResponse<Void> setChannelShared(@RequestParam String serial,
                                              @RequestParam String code,
                                              @RequestParam(defaultValue = "false") Boolean shared) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setShared(shared);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    @Operation(summary = "设置流传输模式")
    @PostMapping("/setmediatransport")
    public ApiResponse<Void> setMediaTransport(
            @RequestParam String serial,
            @RequestParam(value = "media_transport") String mediaTransport,
            @RequestParam(value = "media_transport_mode", defaultValue = "passive") String mode) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        // 取值必须校验：它会被直接拼进 SIP INVITE 的 m= 行（附录 G），
        // 写入脏值后该设备后续所有点播都协商失败，界面只表现为「点播不出画面」。
        applyMediaTransport(device, mediaTransport, mode);
        device.setUpdateTime(LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /** {@code setmediatransport} 与 {@code setinfo} 共用，避免两处规则漂移。 */
    private void applyMediaTransport(GbDevice device, String mediaTransport, String mode) {
        String transport = mediaTransport == null ? "" : mediaTransport.trim().toUpperCase();
        if (!"UDP".equals(transport) && !"TCP".equals(transport)) {
            throw BizException.badRequest("media_transport 只能是 UDP 或 TCP，收到：" + mediaTransport);
        }
        // TCP 还要区分被动/主动：模式会被写进 INVITE 的 a=setup / y= 协商，
        // 填错会导致协商失败（表现为点播不出画面）。
        String transportMode = "passive";
        if ("TCP".equals(transport)) {
            String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"passive".equals(m) && !"active".equals(m)) {
                throw BizException.badRequest("media_transport_mode 只能是 passive 或 active，收到：" + mode);
            }
            transportMode = m;
        }
        device.setMediaTransport(transport);
        device.setMediaTransportMode(transportMode);
    }

    @Operation(summary = "编辑设备")
    @PostMapping("/setinfo")
    public ApiResponse<Void> setDeviceInfo(@RequestParam String serial,
                                           @RequestParam(value = "custom_name", required = false) String customName,
                                           @RequestParam(value = "media_transport", required = false) String mediaTransport,
                                           @RequestParam(value = "media_transport_mode", required = false) String mediaTransportMode,
                                           @RequestParam(value = "stream_index", required = false) String streamIndex,
                                           @RequestParam(required = false) String charset,
                                           @RequestParam(value = "catalog_interval", required = false) Integer catalogInterval,
                                           @RequestParam(value = "subscribe_interval", required = false) Integer subscribeInterval,
                                           @RequestParam(value = "catalog_subscribe", required = false) Boolean catalogSubscribe,
                                           @RequestParam(value = "alarm_subscribe", required = false) Boolean alarmSubscribe,
                                           @RequestParam(value = "position_subscribe", required = false) Boolean positionSubscribe,
                                           @RequestParam(value = "ptz_subscribe", required = false) Boolean ptzSubscribe,
                                           @RequestParam(value = "record_center", required = false) Boolean recordCenter,
                                           @RequestParam(required = false) String password) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        if (customName != null) {
            device.setCustomName(customName);
        }
        if (mediaTransport != null) {
            // 走与 setmediatransport 相同的校验，避免两处规则不一致
            applyMediaTransport(device, mediaTransport, mediaTransportMode);
        }
        if (streamIndex != null) {
            // 码流索引取值有限，非法值直接拒绝而不是静默回落到 auto ——
            // 用户以为自己配了子码流，实际却在拉主码流，这种错配很难自查
            StreamIndex si = StreamIndex.of(streamIndex);
            if (!si.getValue().equalsIgnoreCase(streamIndex.trim())) {
                throw BizException.badRequest("stream_index 取值只能是 "
                        + java.util.Arrays.stream(StreamIndex.values())
                                .map(StreamIndex::getValue).collect(java.util.stream.Collectors.joining(" / "))
                        + "，收到：" + streamIndex);
            }
            device.setStreamIndex(si.getValue());
        }
        if (charset != null) {
            device.setCharset(charset);
        }
        if (catalogInterval != null) {
            device.setCatalogInterval(catalogInterval);
        }
        if (subscribeInterval != null) {
            device.setSubscribeInterval(subscribeInterval);
        }
        if (catalogSubscribe != null) {
            device.setCatalogSubscribe(catalogSubscribe);
        }
        if (alarmSubscribe != null) {
            device.setAlarmSubscribe(alarmSubscribe);
        }
        if (positionSubscribe != null) {
            device.setPositionSubscribe(positionSubscribe);
        }
        if (ptzSubscribe != null) {
            device.setPtzSubscribe(ptzSubscribe);
        }
        if (recordCenter != null) {
            device.setRecordCenter(recordCenter);
        }
        if (password != null) {
            device.setPassword(password);
        }
        device.setUpdateTime(LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /**
     * 设置设备的 SIP 密码记录（仅平台侧留存）。不参与注册鉴权：鉴权用系统级统一密码
     * {@code gb28181.sip-password}。Authorization.username 只是查密码用的键，不一定是设备编号，
     * 无法按设备编号可靠区分密码；这里存的值仅供运维排查。
     */
    @Operation(summary = "设置设备的 SIP 密码记录（仅平台侧留存，供运维参考）")
    @PostMapping("/setpassword")
    public ApiResponse<SetPasswordVo> setPassword(
            @RequestParam String serial,
            @RequestParam(required = false) String password) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        device.setPassword(password == null || password.isBlank() ? null : password);
        device.setUpdateTime(LocalDateTime.now());
        deviceService.updateDevice(device);
        SetPasswordVo vo = new SetPasswordVo();
        vo.setDeviceId(serial);
        vo.setHasPassword(device.getPassword() != null);
        vo.setMessage("已记录该设备的密码（仅供运维参考）；实际注册鉴权用 gb28181.sip-password");
        return ApiResponse.ok(vo);
    }

    /**
     * 一键采集设备全量数据（目录 + 信息 + 状态）。忽略 {@code collect-interval-seconds} 节流，
     * 适合设备数据异常时手工刷新。采集是异步的，返回后 1~3 秒再查 {@code /channellist}。
     */
    @Operation(summary = "一键采集设备全量数据（目录 + 信息 + 状态）")
    @PostMapping("/collect")
    public ApiResponse<FetchResultVo> collect(@RequestParam String serial) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        if (!Boolean.TRUE.equals(device.getOnline())) {
            throw BizException.badRequest("设备当前离线，无法采集数据：" + serial);
        }
        collectService.collectOnOnline(serial, true);
        deviceService.logStatus(serial, null, null, null, "手动全量采集", "manual");
        return ApiResponse.ok(FetchResultVo.ok(serial,
                "已下发 Catalog/DeviceInfo/DeviceStatus 查询，结果异步落库，稍后查询 channellist 可见"));
    }

    /**
     * 删除设备（同时删除其通道与预置位档案）。
     *
     * <h3>为什么在线设备也能删了</h3>
     * 早期一刀切「设备在线，无法删除；请先断开设备连接」。这条规则对
     * <b>国标设备</b>成立：它自己在往平台 REGISTER，删了立刻又注册回来，
     * 所以必须先让它离线。
     *
     * <p>但对 <b>SDK 直连设备（大华等）完全不适用</b>：连接是平台主动发起的，
     * 设备侧不会「自己断开」，用户无从下手 —— 表现就是「大华设备永远删不掉」。
     *
     * <p>现在按接入方式区分：
     * <ul>
     *   <li>SDK 设备：<b>允许在线删除</b>，删之前先登出 SDK 会话
     *       （否则设备侧一直显示「有客户端登录」，原生句柄也泄漏）；</li>
     *   <li>国标设备：保持原规则，在线时拒绝。</li>
     * </ul>
     */
    @Operation(summary = "删除设备")
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String serial) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        boolean sdkDevice = io.github.rurulalanana.deviceaccess.DeviceKind.of(device.getSource())
                == io.github.rurulalanana.deviceaccess.DeviceKind.DAHUA;
        if (Boolean.TRUE.equals(device.getOnline()) && !sdkDevice) {
            throw BizException.badRequest("设备在线，无法删除；请先断开设备连接");
        }
        if (sdkDevice) {
            // 先断 SDK 会话再删档案：顺序反了就拿不到 host:port，会话会残留
            dahuaCapability.logoutQuietly(device.getDeviceId());
        }
        deviceService.removeDevice(device);
        return ApiResponse.ok();
    }

    @Operation(summary = "指定设备推流 SMS")
    @PostMapping("/setsms")
    public ApiResponse<Void> setSms(@RequestParam String serial,
                                    @RequestParam(value = "sms_id", required = false) String smsId,
                                    @RequestParam(value = "sms_group_id", required = false) String smsGroupId) {
        StpUtil.checkLogin();
        // 单实例部署下只有一个媒体服务，SMS 路由无意义，仅记录配置
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        log.info("设备 {} 指定 SMS: id={} group={}（单实例部署下忽略）", serial, smsId, smsGroupId);
        return ApiResponse.ok();
    }

    @Operation(summary = "自定义预置位名称")
    @PostMapping("/setpresetname")
    public ApiResponse<Void> setPresetName(@RequestParam String serial,
                                           @RequestParam String code,
                                           @RequestParam Integer preset,
                                           @RequestParam(required = false) String name) {
        StpUtil.checkLogin();
        deviceService.savePresetName(code, preset, name);
        return ApiResponse.ok();
    }

    // ==================== 内部 ====================

    private GbChannel requireChannel(String code) {
        GbChannel channel = deviceService.findChannel(code);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + code);
        }
        return channel;
    }

    /**
     * 按「设备编码 + 通道编码」精确定位通道。通道编码只在单设备内唯一，所有写操作都必须走
     * 这个方法：只按 code 查会改到别台设备的同号通道，且用户很难发现。
     */
    private GbChannel requireChannel(String serial, String code) {
        GbChannel channel = deviceService.findChannel(serial, code);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + serial + ":" + code);
        }
        return channel;
    }

    private boolean matchChannel(GbChannel c, ChannelListQuery query) {
        if (query.getOnline() != null && !query.getOnline().equals(c.isOnline())) {
            return false;
        }
        if (query.getShared() != null && !query.getShared().equals(Boolean.TRUE.equals(c.getShared()))) {
            return false;
        }
        if (StringUtils.hasText(query.getSerial()) && !contains(query.getSerial(), c.getDeviceId())) {
            return false;
        }
        if (StringUtils.hasText(query.getCode()) && !contains(query.getCode(), c.getChannelId())) {
            return false;
        }
        if (StringUtils.hasText(query.getCivilcode()) && !query.getCivilcode().equals(c.getCivilCode())) {
            return false;
        }
        if (StringUtils.hasText(query.getBlock()) && !query.getBlock().equals(c.getBlock())) {
            return false;
        }
        if (StringUtils.hasText(query.getDirSerial()) && !query.getDirSerial().equals(c.getParentId())) {
            return false;
        }
        if ("dir".equalsIgnoreCase(query.getChannelType())
                && (c.getParental() == null || c.getParental() == 0)) {
            return false;
        }
        return query.matches(c.getChannelId(), c.getName(), c.getCustomName(),
                c.getManufacturer(), c.getModel(), c.getAddress());
    }

    private static boolean contains(String csv, String value) {
        if (value == null) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (part.trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private DeviceVo deviceView(GbDevice d) {
        DeviceVo vo = new DeviceVo();
        vo.setId(d.getDeviceId());
        vo.setName(d.getName());
        vo.setCustomName(d.getCustomName());
        vo.setType("GB");
        // 接入方式：前端据此决定「在线时能不能删」（SDK 设备可以，国标设备不行）
        vo.setSource(d.getSource());
        vo.setManufacturer(d.getManufacturer());
        vo.setModel(d.getModel());
        vo.setFirmware(d.getFirmware());
        vo.setChannelCount(d.getChannelCount());
        vo.setCivilCode(d.getCivilCode());
        vo.setAddress(d.getAddress());
        vo.setParentId(d.getParentId());
        vo.setLongitude(d.getLongitude());
        vo.setLatitude(d.getLatitude());
        vo.setPtzType(d.getPtzType());
        vo.setStatus(d.getStatus());
        vo.setOnline(Boolean.TRUE.equals(d.getOnline()));
        vo.setRemoteIp(d.getRemoteIp());
        vo.setRemotePort(d.getRemotePort());
        vo.setCommandTransport(d.getTransport());
        vo.setMediaTransport(d.getMediaTransport());
        vo.setMediaTransportMode(d.getMediaTransportMode());
        vo.setStreamIndex(d.getStreamIndex());
        vo.setEndpoint(endpointOf(d));
        vo.setCharset(d.getCharset());
        vo.setCatalogInterval(d.getCatalogInterval());
        vo.setSubscribeInterval(d.getSubscribeInterval());
        vo.setCatalogSubscribe(d.getCatalogSubscribe());
        vo.setAlarmSubscribe(d.getAlarmSubscribe());
        vo.setPositionSubscribe(d.getPositionSubscribe());
        vo.setPtzSubscribe(d.getPtzSubscribe());
        vo.setRecordCenter(d.getRecordCenter());
        vo.setLastRegisterAt(TimeFormats.toDisplay(d.getRegisterTime()));
        vo.setLastKeepaliveAt(TimeFormats.toDisplay(d.getKeepaliveTime()));
        vo.setUpdatedAt(TimeFormats.toDisplay(d.getUpdateTime()));
        vo.setCreatedAt(TimeFormats.toDisplay(d.getCreateTime()));
        return vo;
    }

    /**
     * 拼出设备信令出口地址，形如 {@code udp://183.201.190.57:5060}。
     *
     * <p>地址不全时返回 {@code null}：拼一个 {@code udp://} 出来比不显示更糟，
     * 会被误当成有效地址去排查。
     */
    private String endpointOf(GbDevice d) {
        if (d.getRemoteIp() == null || d.getRemoteIp().isBlank()) {
            return null;
        }
        String proto = d.getTransport() == null || d.getTransport().isBlank()
                ? "UDP" : d.getTransport().toLowerCase(java.util.Locale.ROOT);
        return d.getRemotePort() == null
                ? proto + "://" + d.getRemoteIp()
                : proto + "://" + d.getRemoteIp() + ":" + d.getRemotePort();
    }

    /**
     * 该通道能否切换码流（前端据此决定显不显示码流选择器）。
     *
     * <h3>两条判据</h3>
     * <ul>
     *   <li><b>SDK 直连设备</b>（大华等）：走 RTSP 地址的 {@code subtype} 参数，
     *       恒支持主/子码流，与国标无关</li>
     *   <li><b>国标设备</b>：看目录里上报的 {@code StreamNumberList}（GB/T 28181—2022 A.2.4.3）。
     *       判据实现在 {@link io.github.rurulalanana.starter.common.StreamIndex#supportsMultiStream}，
     *       那里说明了为什么不能只看「设备能推流」。</li>
     * </ul>
     *
     * @param device           通道所属设备（可为 {@code null}，按国标处理）
     * @param streamNumberList 设备上报的码流编号列表，如 {@code "0,1"}
     */
    private static boolean streamSwitchable(GbDevice device, String streamNumberList) {
        // SDK 直连设备：RTSP subtype 是拉流地址的一部分，主/子码流一定可选
        if (device != null
                && io.github.rurulalanana.deviceaccess.DeviceKind.of(device.getSource())
                != io.github.rurulalanana.deviceaccess.DeviceKind.GB28181) {
            return true;
        }
        return io.github.rurulalanana.starter.common.StreamIndex
                .supportsMultiStream(streamNumberList);
    }

    private ChannelVo channelView(GbChannel c) {
        ChannelVo vo = new ChannelVo();
        vo.setId(c.getChannelId());
        vo.setDeviceId(c.getDeviceId());
        GbDevice device = deviceService.findDevice(c.getDeviceId());        vo.setDeviceName(device == null ? null : device.getName());
        vo.setDeviceCustomName(device == null ? null : device.getCustomName());
        vo.setDeviceType("GB");
        vo.setSource(device == null || device.getSource() == null
                ? io.github.rurulalanana.deviceaccess.DeviceKind.GB28181.code()
                : io.github.rurulalanana.deviceaccess.DeviceKind.of(device.getSource()).code());
        vo.setDeviceOnline(device != null && Boolean.TRUE.equals(device.getOnline()));
        vo.setChannel(c.getChannelNo());
        vo.setName(c.getName());
        vo.setCustomName(c.getCustomName());
        vo.setCustomId(c.getCustomId());
        vo.setBlock(c.getBlock());
        vo.setCivilCode(c.getCivilCode());
        vo.setAddress(c.getAddress());
        vo.setManufacturer(c.getManufacturer());
        vo.setModel(c.getModel());
        vo.setOwner(c.getOwner());
        vo.setFirmware(c.getFirmware());
        vo.setSerialNumber(c.getSerialNumber());
        vo.setIpAddress(c.getIpAddress());
        vo.setPort(c.getPort());
        vo.setParental(c.getParental());
        vo.setParentId(c.getParentId());
        vo.setSecrecy(c.getSecrecy());
        vo.setRegisterWay(c.getRegisterWay());
        vo.setStatus(c.getStatus());
        vo.setLongitude(c.getLongitude());
        vo.setLatitude(c.getLatitude());
        vo.setAltitude(c.getAltitude());
        vo.setSpeed(c.getSpeed());
        vo.setDirection(c.getDirection());
        vo.setPtzType(c.getPtzType());
        vo.setBatteryLevel(c.getBatteryLevel());
        vo.setSignalLevel(c.getSignalLevel());
        vo.setResolution(c.getResolution());
        vo.setStreamNumberList(c.getStreamNumberList());
        vo.setStreamSwitchable(streamSwitchable(device, c.getStreamNumberList()));
        vo.setDownloadSpeed(c.getDownloadSpeed());
        vo.setOndemand(c.getOnDemand());
        vo.setAudioEnable(c.getAudio());
        vo.setCloudRecord(c.getCloudRecord());
        vo.setShared(c.getShared());
        vo.setSubCount(0);
        vo.setStreamId(c.getStreamId());
        vo.setNumOutputs(c.getStreamId() == null ? 0 : 1);
        vo.setUpdatedAt(TimeFormats.toDisplay(c.getUpdateTime()));
        vo.setCreatedAt(TimeFormats.toDisplay(c.getCreateTime()));
        // 正在直播时附带播放地址，前端可直接取用
        if (c.getStreamId() != null && !c.getStreamId().isBlank()) {
            // 用 (设备编码, 通道编码) 精确定位：通道编码跨设备可重复
            var session = streamService.session(c.getDeviceId(), c.getChannelId());
            if (session != null) {
                StreamVo urls = streamService.describe(session);
                vo.setWebrtc(urls.getWebrtc());
                vo.setFlv(urls.getFlv());
                vo.setHls(urls.getHls());
                vo.setRtsp(urls.getRtsp());
                vo.setRtmp(urls.getRtmp());
            }
        }
        return vo;
    }

    private List<TreeNodeVo> buildGroupLevel(String parentCode,
                                             Map<String, List<GbDeviceGroup>> byParent,
                                             boolean subfetch) {
        List<TreeNodeVo> out = new ArrayList<>();
        for (GbDeviceGroup g : byParent.getOrDefault(parentCode, List.of())) {
            List<TreeNodeVo> children = subfetch
                    ? buildGroupLevel(g.getGroupCode(), byParent, true) : null;
            out.add(TreeBuilder.groupNode(g.getGroupCode(), g.getGroupName(), children));
        }
        return out;
    }
}
