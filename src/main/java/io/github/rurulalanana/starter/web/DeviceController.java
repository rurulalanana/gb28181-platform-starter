package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.common.TreeBuilder;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.dto.ChannelListQuery;
import io.github.rurulalanana.starter.dto.DeviceListQuery;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbDeviceGroup;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.mapper.GbDeviceGroupMapper;
import io.github.rurulalanana.starter.service.ControlService;
import io.github.rurulalanana.starter.service.DeviceCollectService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.RecordService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.ChannelListVo;
import io.github.rurulalanana.starter.vo.ChannelNavigateVo;
import io.github.rurulalanana.starter.vo.ChannelVo;
import io.github.rurulalanana.starter.vo.DeviceListVo;
import io.github.rurulalanana.starter.vo.DeviceVo;
import io.github.rurulalanana.starter.vo.FetchResultVo;
import io.github.rurulalanana.starter.vo.OnlineStatsVo;
import io.github.rurulalanana.starter.vo.PositionLogListVo;
import io.github.rurulalanana.starter.vo.SetPasswordVo;
import io.github.rurulalanana.starter.vo.StatusLogListVo;
import io.github.rurulalanana.starter.vo.StatusLogVo;
import io.github.rurulalanana.starter.vo.StreamLogVo;
import io.github.rurulalanana.starter.vo.StreamVo;
import io.github.rurulalanana.starter.vo.TreeNodeVo;
import io.github.rurulalanana.starter.vo.TreeVo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备信息接口。 *
 * <pre>
 * GET  /api/v1/device/list              设备列表
 * GET  /api/v1/device/info              单条设备信息
 * GET  /api/v1/device/channellist       通道列表
 * GET  /api/v1/device/channeltree       通道树
 * GET  /api/v1/device/grouptree         分组树
 * GET  /api/v1/device/channelinfo       单条通道信息
 * GET  /api/v1/device/onlinestats       在线统计
 * GET  /api/v1/device/statuslog         状态变更记录
 * GET  /api/v1/device/streamlog         流量统计
 * GET  /api/v1/device/positionlog       位置记录
 * POST /api/v1/device/fetchcatalog      主动抓取目录
 * POST /api/v1/device/setname           自定义设备名称
 * POST /api/v1/device/setchannelname    自定义通道名称
 * ...
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final RecordService recordService;
    private final StreamService streamService;
    private final ControlService controlService;
    private final DeviceCollectService collectService;
    private final GbDeviceGroupMapper groupMapper;
    private final Gb28181Properties props;

    // ==================== 设备 ====================

    /** 设备列表。 */
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

    /** 单条设备信息。 */
    @GetMapping("/info")
    public ApiResponse<DeviceVo> info(@RequestParam String serial) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        return ApiResponse.ok(deviceView(device));
    }

    /** 在线统计。 */
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

    /** 通道列表。 */
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

    /** 单条通道信息。 */
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

    /**
     * 通道树。
     *
     * @param serial   设备编号；为空时返回顶级设备节点
     * @param subfetch 是否递归抓取子节点（false 时只返回一层）
     */
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
                // 懒加载：剥掉 children，只保留一层
                for (TreeNodeVo n : nodes) {
                    n.setChildren(null);
                }
            }
        }
        TreeVo vo = new TreeVo();
        vo.setNodeList(limit > 0 ? slice(nodes, start, limit) : nodes);
        return ApiResponse.ok(vo);
    }

    /** 分组树。 */
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

    /** 位置记录。 */
    @GetMapping("/positionlog")
    public ApiResponse<PositionLogListVo> positionLog(
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit) {
        StpUtil.checkLogin();
        // 位置记录由设备通过 MobilePosition 上报，未开启位置订阅时为空
        PositionLogListVo vo = new PositionLogListVo();
        vo.setLogCount(0);
        vo.setLogList(List.of());
        return ApiResponse.ok(vo);
    }

    // ==================== 抓取类 ====================

    /** 主动抓取下级通道目录。 */
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

    /** 抓取下级设备信息。 */
    @PostMapping("/fetchinfo")
    public ApiResponse<FetchResultVo> fetchInfo(@RequestParam String serial,
                                                @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryDeviceInfo(code == null || code.isBlank() ? serial : code);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 抓取下级通道状态。 */
    @PostMapping("/fetchstatus")
    public ApiResponse<FetchResultVo> fetchStatus(@RequestParam String serial,
                                                  @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        controlService.queryDeviceStatus(channelId);
        return ApiResponse.ok(FetchResultVo.ok(channelId));
    }

    /** 抓取下级通道配置。 */
    @PostMapping("/fetchconfig")
    public ApiResponse<FetchResultVo> fetchConfig(@RequestParam String serial,
                                                  @RequestParam(required = false) String code,
                                                  @RequestParam("config_type") String configType) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        controlService.queryConfig(channelId, configType);
        return ApiResponse.ok(FetchResultVo.ok(channelId));
    }

    /** 抓取预置位。 */
    @PostMapping("/fetchpreset")
    public ApiResponse<FetchResultVo> fetchPreset(@RequestParam String serial,
                                                  @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryPresets(code == null || code.isBlank() ? serial : code);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 抓取看守位信息。 */
    @PostMapping("/fetchhomeposition")
    public ApiResponse<FetchResultVo> fetchHomePosition(@RequestParam String serial,
                                                        @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryHomePosition(code == null || code.isBlank() ? serial : code);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 抓取巡航组列表。 */
    @PostMapping("/fetchcruisetracklist")
    public ApiResponse<FetchResultVo> fetchCruiseList(@RequestParam String serial,
                                                      @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryCruiseTrack(code == null || code.isBlank() ? serial : code, 0);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 抓取巡航轨迹。 */
    @PostMapping("/fetchcruisetrack")
    public ApiResponse<FetchResultVo> fetchCruiseTrack(@RequestParam String serial,
                                                       @RequestParam(required = false) String code,
                                                       @RequestParam(defaultValue = "0") Integer number) {
        StpUtil.checkLogin();
        controlService.queryCruiseTrack(code == null || code.isBlank() ? serial : code, number);
        FetchResultVo vo = FetchResultVo.ok(serial);
        vo.setNumber(number);
        return ApiResponse.ok(vo);
    }

    /** 抓取 PTZ 精确状态。 */
    @PostMapping("/fetchptzposition")
    public ApiResponse<FetchResultVo> fetchPtzPosition(@RequestParam String serial,
                                                       @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.queryPtzPosition(code == null || code.isBlank() ? serial : code);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 抓取存储卡状态。 */
    @PostMapping("/fetchsdcardstatus")
    public ApiResponse<FetchResultVo> fetchSdCardStatus(@RequestParam String serial,
                                                        @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        controlService.querySdCardStatus(code == null || code.isBlank() ? serial : code);
        return ApiResponse.ok(FetchResultVo.ok(serial));
    }

    /** 查询通道巡航列表。 */
    @GetMapping("/getchannelnavigate")
    public ApiResponse<ChannelNavigateVo> getChannelNavigate(@RequestParam String serial,
                                                             @RequestParam(required = false) String code) {
        StpUtil.checkLogin();
        String channelId = code == null || code.isBlank() ? serial : code;
        controlService.queryCruiseTrack(channelId, 0);
        ChannelNavigateVo vo = new ChannelNavigateVo();
        vo.setDeviceId(channelId);
        vo.setSumNum(0);
        vo.setGroupList(List.of());
        return ApiResponse.ok(vo);
    }

    // ==================== 状态 / 流量记录 ====================

    /** 状态变更记录。 */
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
        vo.setLogList(limit > 0 ? slice(list, start, limit) : list);
        return ApiResponse.ok(vo);
    }

    /**
     * 流量统计。
     *
     * <p>嵌入式 ZLMediaKit 未注册 {@code /index/api/*}，无法主动查询流量计数；
     * 如需真实流量，应改为独立部署 MediaServer 并读取其 REST 接口。
     */
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

    /** 自定义设备名称。 */
    @PostMapping("/setname")
    public ApiResponse<Void> setDeviceName(@RequestParam String serial,
                                           @RequestParam(value = "custom_name", required = false) String customName) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        device.setCustomName(customName);
        device.setUpdateTime(java.time.LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /** 自定义通道名称。 */
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

    /** 自定义通道国标编号。 */
    @PostMapping("/setchannelid")
    public ApiResponse<Void> setChannelId(@RequestParam String serial,
                                          @RequestParam String code,
                                          @RequestParam(required = false) String id) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setCustomId(id);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /** 自定义通道云台类型。 */
    @PostMapping("/setchannelptztype")
    public ApiResponse<Void> setChannelPtzType(@RequestParam String serial,
                                               @RequestParam String code,
                                               @RequestParam(value = "ptz_type", defaultValue = "0") Integer ptzType) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setPtzType(ptzType);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /** 自定义通道移动位置。 */
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

    /** 自定义通道属性。 */
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
            case "Port" -> channel.setPort(parseInt(value));
            case "Longitude" -> channel.setLongitude(parseDecimal(value));
            case "Latitude" -> channel.setLatitude(parseDecimal(value));
            default -> throw BizException.badRequest("不支持的属性名：" + key);
        }
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /** 设置通道按需直播。 */
    @PostMapping("/setchannelondemand")
    public ApiResponse<Void> setChannelOndemand(@RequestParam String serial,
                                                @RequestParam String code,
                                                @RequestParam(defaultValue = "true") Boolean ondemand,
                                                @RequestParam(value = "check_outputs", defaultValue = "true")
                                                Boolean checkOutputs) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setOnDemand(ondemand);
        deviceService.updateChannel(channel);
        // 由「按需」切到「常开」时无需处理；反向切换且当前无人观看则停流
        if (Boolean.TRUE.equals(ondemand) && Boolean.TRUE.equals(checkOutputs)) {
            log.info("通道 {} 已设为按需直播，无人观看时将由 ZLM 自动停流", code);
        }
        return ApiResponse.ok();
    }

    /** 设置通道云端录像。 */
    @PostMapping("/setchannelcloudrecord")
    public ApiResponse<Void> setChannelCloudRecord(@RequestParam String serial,
                                                   @RequestParam String code,
                                                   @RequestParam(defaultValue = "false") Boolean cloudrecord) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setCloudRecord(cloudrecord);
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /** 设置通道音频开关。 */
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

    /** 设置通道分享开关。 */
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

    /** 设置流传输模式。 */
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
        device.setMediaTransport(mediaTransport);
        device.setUpdateTime(java.time.LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /** 编辑设备。 */
    @PostMapping("/setinfo")
    public ApiResponse<Void> setDeviceInfo(@RequestParam String serial,
                                           @RequestParam(value = "custom_name", required = false) String customName,
                                           @RequestParam(value = "media_transport", required = false) String mediaTransport,
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
            device.setMediaTransport(mediaTransport);
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
        device.setUpdateTime(java.time.LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /**
     * 设置设备的 SIP 密码记录（仅平台侧留存，供运维参考）。
     *
     * <p><b>不参与注册鉴权</b>：注册鉴权用的是系统级统一密码
     * {@code gb28181.sip-password}。SIP 的 Authorization.username 只是查密码用的键，
     * 不一定是设备编号，所以无法按设备编号可靠地区分密码。
     * 这里存的值用于记录「该设备安装时设的密码」，方便运维排查。
     */
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
        device.setUpdateTime(java.time.LocalDateTime.now());
        deviceService.updateDevice(device);
        SetPasswordVo vo = new SetPasswordVo();
        vo.setDeviceId(serial);
        vo.setHasPassword(device.getPassword() != null);
        vo.setMessage("已记录该设备的密码（仅供运维参考）；实际注册鉴权用 gb28181.sip-password");
        return ApiResponse.ok(vo);
    }

    /**
     * 一键采集设备全量数据（目录 + 信息 + 状态）。
     *
     * <p>设备注册时平台会自动采集（受 {@code collect-interval-seconds} 节流）；
     * 本接口忽略节流强制重采，适合设备数据异常时手工刷新。
     *
     * <p>采集是异步的：接口返回后 1~3 秒再查 {@code /channellist} 即可看到通道。
     */
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

    /** 删除离线设备。 */
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam String serial) {
        StpUtil.checkLogin();
        GbDevice device = deviceService.findDevice(serial);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + serial);
        }
        if (Boolean.TRUE.equals(device.getOnline())) {
            throw BizException.badRequest("设备在线，无法删除；请先断开设备连接");
        }
        deviceService.removeDevice(device);
        return ApiResponse.ok();
    }

    /** 指定设备推流 SMS。 */
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

    /** 自定义预置位名称。 */
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

    private boolean matchChannel(GbChannel c, ChannelListQuery query) {
        if (query.getOnline() != null && !query.getOnline().equals(c.isOnline())) {
            return false;
        }
        if (query.getShared() != null && !query.getShared().equals(Boolean.TRUE.equals(c.getShared()))) {
            return false;
        }
        if (notBlank(query.getSerial()) && !contains(query.getSerial(), c.getDeviceId())) {
            return false;
        }
        if (notBlank(query.getCode()) && !contains(query.getCode(), c.getChannelId())) {
            return false;
        }
        if (notBlank(query.getCivilcode()) && !query.getCivilcode().equals(c.getCivilCode())) {
            return false;
        }
        if (notBlank(query.getBlock()) && !query.getBlock().equals(c.getBlock())) {
            return false;
        }
        if (notBlank(query.getDirSerial()) && !query.getDirSerial().equals(c.getParentId())) {
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

    private ChannelVo channelView(GbChannel c) {
        ChannelVo vo = new ChannelVo();
        vo.setId(c.getChannelId());
        vo.setDeviceId(c.getDeviceId());
        GbDevice device = deviceService.findDevice(c.getDeviceId());
        vo.setDeviceName(device == null ? null : device.getName());
        vo.setDeviceCustomName(device == null ? null : device.getCustomName());
        vo.setDeviceType("GB");
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
        vo.setDownloadSpeed(c.getDownloadSpeed());
        vo.setOndemand(c.getOnDemand());
        vo.setAudioEnable(c.getAudio());
        vo.setCloudRecord(c.getCloudRecord());
        vo.setShared(c.getShared());
        vo.setSubCount(0);
        vo.setStreamId(c.getStreamId());
        vo.setNumOutputs(c.getStreamId() == null ? 0 : 1);
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

    private static <T> List<T> slice(List<T> all, int start, int limit) {
        int from = Math.min(Math.max(0, start), all.size());
        int to = Math.min(from + Math.max(0, limit), all.size());
        return all.subList(from, to);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static Integer parseInt(String v) {
        try {
            return v == null || v.isBlank() ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.math.BigDecimal parseDecimal(String v) {
        try {
            return v == null || v.isBlank() ? null : new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
