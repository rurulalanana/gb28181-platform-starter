package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbCascade;
import io.github.rurulalanana.starter.entity.GbCascadeChannel;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.mapper.GbCascadeChannelMapper;
import io.github.rurulalanana.starter.mapper.GbCascadeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 级联配置服务（GB/T 28181—2022 第 8 章）：本级作为下级向上级注册，并把选定通道的目录推给上级。
 * 信令层由 {@code CascadeManager} 处理，本服务负责配置持久化与目录推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CascadeService {

    private final GbCascadeMapper cascadeMapper;
    private final GbCascadeChannelMapper cascadeChannelMapper;
    private final DeviceService deviceService;
    private final Gb28181SipClientProvider clientProvider;

    // ==================== 配置 CRUD ====================

    /** 级联配置列表。 */
    public List<GbCascade> list(String q, Boolean enable, Boolean online) {
        LambdaQueryWrapper<GbCascade> query = Wrappers.<GbCascade>lambdaQuery()
                .eq(enable != null, GbCascade::getEnabled, enable)
                .eq(online != null, GbCascade::getOnline, online);
        if (q != null && !q.isBlank()) {
            query.and(w -> w.like(GbCascade::getName, q).or().like(GbCascade::getParentId, q));
        }
        return cascadeMapper.selectList(query.orderByDesc(GbCascade::getCreateTime));
    }

    /** 单条级联配置。 */
    public GbCascade find(String cascadeId) {
        if (cascadeId == null || cascadeId.isBlank()) {
            throw BizException.badRequest("缺少级联配置 ID");
        }
        return cascadeMapper.selectOne(Wrappers.<GbCascade>lambdaQuery()
                .eq(GbCascade::getCascadeId, cascadeId).last("limit 1"));
    }

    /** 全部已启用的级联配置（上行链路维护用）。 */
    public List<GbCascade> enabledCascades() {
        return cascadeMapper.selectList(Wrappers.<GbCascade>lambdaQuery()
                .eq(GbCascade::getEnabled, true));
    }

    /** 按上级平台编码查询本级配置（上行方向识别「这条查询来自哪个上级」）。 */
    public GbCascade findByParentId(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        return cascadeMapper.selectOne(Wrappers.<GbCascade>lambdaQuery()
                .eq(GbCascade::getParentId, parentId).last("limit 1"));
    }

    /**
     * 更新在线状态与失败原因。必须写失败原因：只置 offline 时运维无法区分「上级没开机」
     * 「密码不对」「上级没加白名单」，三者处置方式完全不同。
     *
     * @param error 失败原因；{@code null} 表示无错误（成功时清空）
     */
    public void updateOnlineState(String cascadeId, boolean online, String error) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            return;
        }
        GbCascade patch = new GbCascade();
        patch.setId(cascade.getId());
        patch.setOnline(online);
        patch.setUpdateTime(LocalDateTime.now());
        if (error != null) {
            patch.setRemark(trimTo(error, 200));
        } else if (online) {
            // 注册成功时清掉上次的失败原因，否则界面会一直挂着一条过期报错
            patch.setRemark(null);
        }
        cascadeMapper.updateById(patch);
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 按上级平台编号查询。 */
    public GbCascade findByParent(String parentId) {
        return cascadeMapper.selectOne(Wrappers.<GbCascade>lambdaQuery()
                .eq(GbCascade::getParentId, parentId).last("limit 1"));
    }

    /** 保存（新增或更新）级联配置。 */
    @Transactional
    public String save(GbCascade cascade) {
        if (cascade.getParentId() == null || cascade.getParentId().isBlank()) {
            throw BizException.badRequest("上级平台编号（Serial）不能为空");
        }
        GbCascade existing = cascade.getCascadeId() == null || cascade.getCascadeId().isBlank()
                ? findByParent(cascade.getParentId())
                : find(cascade.getCascadeId());
        if (existing == null) {
            cascade.setCascadeId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            cascade.setCreateTime(LocalDateTime.now());
            cascade.setUpdateTime(LocalDateTime.now());
            if (cascade.getEnabled() == null) {
                cascade.setEnabled(false);
            }
            if (cascade.getOnline() == null) {
                cascade.setOnline(false);
            }
            cascadeMapper.insert(cascade);
        } else {
            cascade.setId(existing.getId());
            cascade.setCascadeId(existing.getCascadeId());
            cascade.setUpdateTime(LocalDateTime.now());
            cascadeMapper.updateById(cascade);
        }
        return cascade.getCascadeId();
    }

    /** 设置启用状态。 */
    @Transactional
    public void setEnable(String cascadeId, boolean enable) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        cascade.setEnabled(enable);
        cascade.setUpdateTime(LocalDateTime.now());
        cascadeMapper.updateById(cascade);
    }

    /** 设置共享所有通道。 */
    @Transactional
    public void setShareAllChannel(String cascadeId, boolean shareAll, boolean cleanRelated) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        cascade.setShareAllChannel(shareAll);
        cascade.setUpdateTime(LocalDateTime.now());
        cascadeMapper.updateById(cascade);
        if (cleanRelated) {
            cascadeChannelMapper.delete(Wrappers.<GbCascadeChannel>lambdaQuery()
                    .eq(GbCascadeChannel::getCascadeId, cascadeId));
        }
    }

    /** 删除级联配置。 */
    @Transactional
    public void remove(String cascadeId) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            return;
        }
        cascadeChannelMapper.delete(Wrappers.<GbCascadeChannel>lambdaQuery()
                .eq(GbCascadeChannel::getCascadeId, cascadeId));
        cascadeMapper.deleteById(cascade.getId());
    }

    /** 更新上级在线状态（由信令回调驱动）。 */
    @Transactional
    public void setOnline(String parentId, boolean online) {
        GbCascade cascade = findByParent(parentId);
        if (cascade == null) {
            return;
        }
        cascade.setOnline(online);
        cascade.setUpdateTime(LocalDateTime.now());
        cascadeMapper.updateById(cascade);
    }

    // ==================== 共享通道 ====================

    /** 某级联配置下已共享的通道。 */
    public List<GbCascadeChannel> sharedChannels(String cascadeId) {
        return cascadeChannelMapper.selectList(Wrappers.<GbCascadeChannel>lambdaQuery()
                .eq(GbCascadeChannel::getCascadeId, cascadeId));
    }

    /**
     * 已共享通道的键集合，元素格式 {@code deviceId:channelId}。
     * 必须带设备编码：通道编码只在单设备内唯一，实测十几台设备共用
     * {@code 34020000001320000001}，只用通道编码做键会把其余同号通道全标成「已共享」并写进库。
     */
    public java.util.Set<String> sharedChannelKeys(String cascadeId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (GbCascadeChannel c : sharedChannels(cascadeId)) {
            if (!Boolean.TRUE.equals(c.getShared())) {
                continue;
            }
            keys.add(channelKey(c.getDeviceId(), c.getChannelId()));
        }
        return keys;
    }

    /**
     * 判断某通道是否已共享给该级联。
     *
     * @param deviceId 设备编码；为空时退化为只按通道编码匹配（仅用于兼容老数据）
     */
    public boolean isChannelShared(String cascadeId, String deviceId, String channelId) {
        if (channelId == null) {
            return false;
        }
        for (GbCascadeChannel c : sharedChannels(cascadeId)) {
            if (!Boolean.TRUE.equals(c.getShared())) {
                continue;
            }
            if (!channelId.equals(c.getChannelId())) {
                continue;
            }
            if (deviceId == null || c.getDeviceId() == null || deviceId.equals(c.getDeviceId())) {
                return true;
            }
        }
        return false;
    }

    /** 通道唯一键：{@code deviceId:channelId}。 */
    public static String channelKey(String deviceId, String channelId) {
        return (deviceId == null ? "" : deviceId) + ":" + channelId;
    }

    /** 保存共享通道（{@code channels} 格式为 {@code serial:code}）。 */
    @Transactional
    public int saveChannels(String cascadeId, List<String> channels) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        /*
         * 语义必须是全量覆盖而不是追加：界面上是勾选框，取消勾选期望「不再共享」；
         * 只做 upsert 会让取消勾选静默失效（界面还显示保存成功）。
         * 级联共享是把本级通道暴露给外部平台，方向上的错误必须失败得明显。
         * 空列表表示全部取消共享，也是合法操作。
         */
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String item : channels == null ? java.util.Collections.<String>emptyList() : channels) {
            String[] pair = parseChannelRef(item);
            if (pair != null) {
                keep.add(channelKey(pair[0], pair[1]));
            }
        }
        List<GbCascadeChannel> existing = cascadeChannelMapper.selectList(
                Wrappers.<GbCascadeChannel>lambdaQuery()
                        .eq(GbCascadeChannel::getCascadeId, cascadeId));
        int removed = 0;
        for (GbCascadeChannel sc : existing) {
            if (!keep.contains(channelKey(sc.getDeviceId(), sc.getChannelId()))) {
                cascadeChannelMapper.deleteById(sc.getId());
                removed++;
            }
        }
        int count = 0;
        for (String key : keep) {
            String[] pair = splitKey(key);
            upsertShared(cascade, pair[0], pair[1], true);
            count++;
        }
        if (removed > 0) {
            log.info("级联 {} 取消共享 {} 路通道", cascadeId, removed);
        }
        // 返回「当前共享总数」而不是「本次新增数」：返回新增数会让用户以为「我只共享了 1 路」而实际有 20 路
        return count;
    }

    /** 移除共享通道。 */
    @Transactional
    public int removeChannels(String cascadeId, List<String> channels) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        int count = 0;
        for (String item : channels) {
            String[] pair = parseChannelRef(item);
            if (pair == null) {
                continue;
            }
            upsertShared(cascade, pair[0], pair[1], false);
            count++;
        }
        return count;
    }

    /**
     * 共享 / 取消共享单个节点。
     *
     * @param deviceId 为 {@code null} 时按通道编码匹配（兼容老调用）
     */
    @Transactional
    public void shareNode(String cascadeId, String deviceId, String channelId, boolean share) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        upsertShared(cascade, deviceId, channelId, share);
    }

    private void upsertShared(GbCascade cascade, String deviceId, String channelId,
                              boolean shared) {
        GbCascadeChannel existing = cascadeChannelMapper.selectOne(
                Wrappers.<GbCascadeChannel>lambdaQuery()
                        .eq(GbCascadeChannel::getCascadeId, cascade.getCascadeId())
                        .eq(GbCascadeChannel::getChannelId, channelId)
                        .eq(deviceId != null, GbCascadeChannel::getDeviceId, deviceId)
                        .last("limit 1"));
        if (existing == null) {
            existing = new GbCascadeChannel();
            existing.setCascadeId(cascade.getCascadeId());
            existing.setParentId(cascade.getParentId());
            existing.setDeviceId(deviceId);
            existing.setChannelId(channelId);
            existing.setShared(shared);
            existing.setPushed(false);
            existing.setCreateTime(LocalDateTime.now());
            cascadeChannelMapper.insert(existing);
        } else {
            existing.setShared(shared);
            cascadeChannelMapper.updateById(existing);
        }
    }

    // ==================== 目录推送 ====================

    // 目录推送与上行注册都在 CascadeLinkService（那里持有真正的上行链路）。
    // 直接调 client.sendMessage(parentId, ...) 会失败：它要先在会话表里查上级地址，而上级从不注册到本级。

    /** 按上级编号解析级联配置（{@code id} 与 {@code serial} 二选一）。 */
    public GbCascade resolveCascade(String cascadeId) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            cascade = findByParent(cascadeId);
        }
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        return cascade;
    }

    /** 收集要共享的通道：共享全部时取所有通道，否则取显式共享的。 */
    private List<GbChannel> collectSharedChannels(GbCascade cascade) {
        List<GbChannel> all = deviceService.allChannels();
        if (Boolean.TRUE.equals(cascade.getShareAllChannel())) {
            return all;
        }
        List<String> sharedKeys = new ArrayList<>(sharedChannelKeys(cascade.getCascadeId()));
        java.util.Set<String> keySet = new java.util.HashSet<>(sharedKeys);
        List<GbChannel> out = new ArrayList<>();
        for (GbChannel c : all) {
            if (keySet.contains(channelKey(c.getDeviceId(), c.getChannelId()))) {
                out.add(c);
            }
        }
        return out;
    }

    private void markPushed(String cascadeId, List<GbChannel> channels) {
        for (GbChannel c : channels) {
            GbCascadeChannel cc = cascadeChannelMapper.selectOne(
                    Wrappers.<GbCascadeChannel>lambdaQuery()
                            .eq(GbCascadeChannel::getCascadeId, cascadeId)
                            .eq(GbCascadeChannel::getChannelId, c.getChannelId())
                            .eq(GbCascadeChannel::getDeviceId, c.getDeviceId())
                            .last("limit 1"));
            if (cc == null) {
                cc = new GbCascadeChannel();
                cc.setCascadeId(cascadeId);
                cc.setDeviceId(c.getDeviceId());
                cc.setChannelId(c.getChannelId());
                cc.setShared(true);
                cc.setCreateTime(LocalDateTime.now());
            }
            cc.setPushed(true);
            cc.setPushTime(LocalDateTime.now());
            if (cc.getId() == null) {
                cascadeChannelMapper.insert(cc);
            } else {
                cascadeChannelMapper.updateById(cc);
            }
        }
    }

    /** 通道 → 目录项（级联推送时用自定义编号覆盖国标编号）。 */
    public CatalogItem toCatalogItem(GbChannel c, String event) {
        String exportedId = c.getCustomId() != null && !c.getCustomId().isBlank()
                ? c.getCustomId() : c.getChannelId();
        return CatalogItem.builder()
                .deviceId(exportedId)
                .name(c.displayName())
                .manufacturer(c.getManufacturer())
                .model(c.getModel())
                .civilCode(c.getCivilCode())
                .block(c.getBlock())
                .address(c.getAddress())
                .parental(c.getParental())
                .parentId(c.getParentId())
                .registerWay(c.getRegisterWay())
                .secrecy(c.getSecrecy())
                .status(c.getStatus())
                .ipAddress(c.getIpAddress())
                .port(c.getPort())
                .longitude(c.getLongitude() == null ? null : c.getLongitude().doubleValue())
                .latitude(c.getLatitude() == null ? null : c.getLatitude().doubleValue())
                .ptzType(c.getPtzType())
                .resolution(c.getResolution())
                .downloadSpeed(c.getDownloadSpeed())
                .businessGroupId(c.getBusinessGroupId())
                // 事件类型：ON 首次全量、UPDATE 变更、DEL 删除；不写会让上级把每条都当新增，删掉的通道永远留着
                .event(event == null || event.isBlank() ? "ON" : event)
                .build();
    }

    private Gb28181SipClient requireClient() {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法推送目录");
        }
        return client;
    }

    private static String[] parseChannelRef(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        String v = item.trim();
        int colon = v.indexOf(':');
        if (colon > 0 && colon + 1 < v.length()) {
            // serial:code —— 标准形式
            return new String[]{v.substring(0, colon), v.substring(colon + 1)};
        }
        // 只有一段：视为通道编号，设备编码留空（兼容老调用与脏数据）
        return new String[]{null, v};
    }

    /** 拆 {@code deviceId:channelId}。 */
    private static String[] splitKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return new String[]{null, key};
        }
        String dev = key.substring(0, colon);
        return new String[]{dev.isEmpty() ? null : dev, key.substring(colon + 1)};
    }
}
