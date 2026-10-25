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

    /** 级联通道列表。 */
    @GetMapping("/channellist")
    public ApiResponse<CascadeChannelListVo> channelList(
            @RequestParam String id,
            @RequestParam(value = "channel_type", defaultValue = "all") String channelType,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean online,
            @RequestParam(defaultValue = "false") Boolean related) {
        StpUtil.checkLogin();
        GbCascade cascade = cascadeService.resolveCascade(id);
        List<String> sharedIds = cascadeService.sharedChannelIds(cascade.getCascadeId());
        List<GbChannel> all = deviceService.allChannels();
        List<CascadeChannelVo> list = new ArrayList<>();
        int sharedCount = 0;
        for (GbChannel c : all) {
            boolean isShared = Boolean.TRUE.equals(cascade.getShareAllChannel())
                    || sharedIds.contains(c.getChannelId());
            if (isShared) {
                sharedCount++;
            }
            if (Boolean.TRUE.equals(related) && !isShared) {
                continue;
            }
            if (online != null && !online.equals(c.isOnline())) {
                continue;
            }
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase();
                String name = c.displayName() == null ? "" : c.displayName().toLowerCase();
                if (!c.getChannelId().toLowerCase().contains(needle) && !name.contains(needle)) {
                    continue;
                }
            }
            CascadeChannelVo row = new CascadeChannelVo();
            row.setCascadeId(isShared ? cascade.getCascadeId() : null);
            row.setId(c.getChannelId());
            row.setDeviceId(c.getDeviceId());
            row.setName(c.getName());
            row.setCustomName(c.getCustomName());
            row.setCustomId(c.getCustomId());
            row.setDeviceOnline(c.isOnline());
            row.setChannel(c.getChannelNo());
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
            list.add(row);
        }
        CascadeChannelListVo vo = new CascadeChannelListVo();
        vo.setChannelCount(list.size());
        vo.setChannelRelateCount(sharedCount);
        vo.setShareAllChannel(cascade.getShareAllChannel());
        vo.setChannelList(PageQuery.slice(list, start, limit));
        return ApiResponse.ok(vo);
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
        String channelId = code == null || code.isBlank() ? serial : code;
        cascadeService.shareNode(cascadeId, channelId, Boolean.TRUE.equals(share));
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

    private CascadeVo cascadeView(GbCascade c) {
        CascadeVo vo = new CascadeVo();
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
        vo.setCreatedAt(TimeFormats.toDisplay(c.getCreateTime()));
        vo.setUpdatedAt(TimeFormats.toDisplay(c.getUpdateTime()));
        return vo;
    }
}
