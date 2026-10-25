package io.github.rurulalanana.starter.web;

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
import io.github.rurulalanana.starter.vo.PositionLogVo;
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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
    private final ChannelRecordPolicy recordPolicy;
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
        vo.setNodeList(limit > 0 ? PageQuery.slice(nodes, start, limit) : nodes);
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
        vo.setLogList(limit > 0 ? PageQuery.slice(list, start, limit) : list);
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
        device.setUpdateTime(LocalDateTime.now());
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

    /**
     * 自定义通道国标编号。
     *
     * <p>用于「上级平台看到的通道编号」与本地实际编号不一致的场景（下级平台
     * 通道编号冲突、需要对接到第三方规范）。改的是 {@code custom_id}，
     * 不动 {@code channel_id} —— 后者是设备实际使用的编号，改了就连不上设备。
     *
     * <p><b>必须按 serial + code 精确定位</b>：通道编码只在单设备内唯一，
     * 多设备环境里两台设备都可能有 {@code ...1320000001}，
     * 只按 code 查会改到另一台设备的同名通道上。
     */
    @PostMapping("/setchannelid")
    public ApiResponse<Void> setChannelId(@RequestParam String serial,
                                          @RequestParam String code,
                                          @RequestParam(required = false) String id) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(serial, code);
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
            case "Port" -> channel.setPort(Numbers.intOrNull(value));
            case "Longitude" -> channel.setLongitude(Numbers.decimalOrNull(value));
            case "Latitude" -> channel.setLatitude(Numbers.decimalOrNull(value));
            default -> throw BizException.badRequest("不支持的属性名：" + key);
        }
        deviceService.updateChannel(channel);
        return ApiResponse.ok();
    }

    /**
     * 设置通道按需直播。
     *
     * <p><b>与云端录像互斥</b>：两者的语义直接冲突，不可能同时成立 ——
     * <ul>
     *   <li><b>按需直播</b> = 没人看就停流，省设备带宽；</li>
     *   <li><b>云端录像</b> = 平台持续录像，必须有流一直在推。</li>
     * </ul>
     * 「有人看就录、没人看就停」录出来的必然是残缺片段，
     * 用户会以为录像功能坏了。因此这里强制互斥：开按需就关录像（并归档已有片段）。
     */
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
     * 设置通道云端录像。
     *
     * <p><b>开关必须同步内存镜像</b>：流上线回调读的是镜像而不是库表
     * （ZLM 回调线程不能查库），只改库不改镜像的话，开关永远不生效。
     *
     * <p><b>开启时若无流则主动拉流</b>：录像必须有流可录（ZLM 的
     * {@code mk_recorder_start} 按 app/stream 找媒体源，源不存在直接失败）。
     * 早期实现只在「流已在播」时才开录，于是用户在通道列表打开开关后
     * 什么都看不到 —— 没有 INVITE、没有流、没有录像，只能手动点一次播放
     * 才会触发。云端录像的语义就是「平台持续把这段录像存下来」，
     * 用户不应再手动拉流，故这里直接点播（点播成功后
     * {@code onSessionOnline} 会按开关自动开录）。
     *
     * <p>关闭时若在录，立即停止并归档 —— 否则开关要到「下一次流上下线」
     * 才体现，用户会以为功能坏了。
     */
    @PostMapping("/setchannelcloudrecord")
    public ApiResponse<Void> setChannelCloudRecord(@RequestParam String serial,
                                                   @RequestParam String code,
                                                   @RequestParam(defaultValue = "false") Boolean cloudrecord) {
        StpUtil.checkLogin();
        GbChannel channel = requireChannel(code);
        channel.setCloudRecord(cloudrecord);
        // 互斥：开云端录像必须关掉按需直播。
        //
        // 「按需」= 没人看就停流，而云端录像要求流一直推着；
        // 二者同开只能录出「有人看时的那几段」，用户会以为录像坏了。
        // 这里是 {@link #setChannelOndemand} 的反方向，两条路径都要兜住，
        // 否则先开哪个就取决于操作顺序（实测踩过）。
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
                // 用库里的 device_id 而不是入参 serial：serial 由前端传入，
                // 可能是平台自身编码（前端 ChannelView 的 row.deviceId 在某些
                // 视图里就是平台编码）或任意值。拿它去点播会生成错误的流名
                // （rtp/<错设备>_<通道>），INVITE 失败还会留下僵尸会话，
                // 使该通道的云端录像长期找不到媒体源。库里的 device_id
                // 才是通道真正的所属设备。
                String deviceId = channel.getDeviceId() != null
                        ? channel.getDeviceId() : serial;
                // 直接调 startLive 而不是先判断会话是否存在：它内部会区分
                // 「可复用的活会话」与「已关闭/无流的死会话」，后者会重建会话
                // 并发起 INVITE —— 这正是开关打开后能真正拉到流的原因。
                // 已可复用时它是空操作（不重复 INVITE）。
                streamService.startLive(deviceId, code);
                log.info("通道 {} 开启云端录像，已确保流在线（device={}，开录由流上线回调触发）",
                        code, deviceId);
            } else if (recordService.isRecording(code)) {
                recordService.stopCloudRecord(code);
                log.info("通道 {} 已关闭录像并归档", code);
            }
        } catch (Exception e) {
            // 开关本身已生效；开/停录失败只告警，不回滚开关（下一次流上下线会重试）
            log.warn("按开关即时开/停录像失败: channel={} {}", code, e.getMessage());
        }
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
        // 取值必须校验：这个值会被直接拼进 SIP INVITE 的 m= 行（附录 G），
        // 写进 BOGUS 之类脏值后该设备后续所有点播都会协商失败，
        // 而界面上只表现为「点播不出画面」，排查成本极高。
        applyMediaTransport(device, mediaTransport, mode);
        device.setUpdateTime(LocalDateTime.now());
        deviceService.updateDevice(device);
        return ApiResponse.ok();
    }

    /**
     * 校验并写入流传输模式。
     *
     * <p>{@code setmediatransport} 与 {@code setinfo} 共用，避免两处规则漂移。
     */
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

    /** 编辑设备。 */
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
        device.setUpdateTime(LocalDateTime.now());
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

    /**
     * 按「设备编码 + 通道编码」精确定位通道。
     *
     * <p>通道编码只在单设备内唯一，凡是<b>写操作</b>都必须走这个方法：
     * 只按 code 查在多设备环境里会改到别人的同号通道（而且用户完全看不出来，
     * 直到某天发现另一台设备的通道名/编号被改了）。
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
        vo.setStreamNumberList(c.getStreamNumberList());
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
