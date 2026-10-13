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
 * 级联配置服务（GB/T 28181—2022 第 8 章）。
 *
 * <p>本级作为下级向上级平台注册，并把选定通道的目录推送给上级。
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

    /** 已共享的通道编号集合。 */
    public List<String> sharedChannelIds(String cascadeId) {
        List<String> ids = new ArrayList<>();
        for (GbCascadeChannel c : sharedChannels(cascadeId)) {
            if (Boolean.TRUE.equals(c.getShared())) {
                ids.add(c.getChannelId());
            }
        }
        return ids;
    }

    /** 保存共享通道（{@code channels} 格式为 {@code serial:code}）。 */
    @Transactional
    public int saveChannels(String cascadeId, List<String> channels) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        int count = 0;
        for (String item : channels) {
            String channelId = parseChannelId(item);
            if (channelId == null) {
                continue;
            }
            upsertShared(cascade, channelId, true);
            count++;
        }
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
            String channelId = parseChannelId(item);
            if (channelId == null) {
                continue;
            }
            upsertShared(cascade, channelId, false);
            count++;
        }
        return count;
    }

    /** 共享 / 取消共享单个节点。 */
    @Transactional
    public void shareNode(String cascadeId, String channelId, boolean share) {
        GbCascade cascade = find(cascadeId);
        if (cascade == null) {
            throw BizException.notFound("级联配置不存在：" + cascadeId);
        }
        upsertShared(cascade, channelId, share);
    }

    private void upsertShared(GbCascade cascade, String channelId, boolean shared) {
        GbCascadeChannel existing = cascadeChannelMapper.selectOne(
                Wrappers.<GbCascadeChannel>lambdaQuery()
                        .eq(GbCascadeChannel::getCascadeId, cascade.getCascadeId())
                        .eq(GbCascadeChannel::getChannelId, channelId)
                        .last("limit 1"));
        if (existing == null) {
            existing = new GbCascadeChannel();
            existing.setCascadeId(cascade.getCascadeId());
            existing.setParentId(cascade.getParentId());
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

    /**
     * 推送全部共享通道目录（标准 9.5.1 的 {@code Catalog} Notify）。
     *
     * <p>标准要求逐个 {@code Item} 或按 {@code CatalogGroupSize} 分组推送；
     * 这里按配置的分组大小切分。
     */
    public int pushCatalog(String cascadeId) {
        GbCascade cascade = resolveCascade(cascadeId);
        List<GbChannel> channels = collectSharedChannels(cascade);
        if (channels.isEmpty()) {
            log.info("级联 {} 无共享通道，跳过目录推送", cascade.getCascadeId());
            return 0;
        }
        Gb28181SipClient client = requireClient();
        int groupSize = cascade.getCatalogGroupSize() == null || cascade.getCatalogGroupSize() <= 0
                ? 1 : cascade.getCatalogGroupSize();
        int pushed = 0;
        for (int i = 0; i < channels.size(); i += groupSize) {
            List<GbChannel> group = channels.subList(i, Math.min(i + groupSize, channels.size()));
            List<CatalogItem> items = new ArrayList<>();
            for (GbChannel c : group) {
                items.add(toCatalogItem(c));
            }
            String xml = ManscdpBuilder.catalogResponse(client.nextSn(),
                    cascade.getLocalId() == null ? cascade.getParentId() : cascade.getLocalId(),
                    items);
            client.sendMessage(cascade.getParentId(), xml);
            pushed += group.size();
        }
        markPushed(cascade.getCascadeId(), channels);
        log.info("级联目录已推送: cascade={} 通道数={}", cascade.getCascadeId(), pushed);
        return pushed;
    }

    /** 推送单条通道目录。 */
    public void pushChannelCatalog(String cascadeId, String channelId, String event) {
        GbCascade cascade = resolveCascade(cascadeId);
        GbChannel channel = deviceService.findChannel(channelId);
        if (channel == null) {
            throw BizException.notFound("通道不存在：" + channelId);
        }
        Gb28181SipClient client = requireClient();
        String xml = ManscdpBuilder.catalogResponse(client.nextSn(),
                cascade.getLocalId() == null ? cascade.getParentId() : cascade.getLocalId(),
                List.of(toCatalogItem(channel)));
        client.sendMessage(cascade.getParentId(), xml);
        log.info("级联单通道目录已推送: cascade={} channel={} event={}",
                cascade.getCascadeId(), channelId, event);
    }

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
        List<String> sharedIds = sharedChannelIds(cascade.getCascadeId());
        List<GbChannel> out = new ArrayList<>();
        for (GbChannel c : all) {
            if (sharedIds.contains(c.getChannelId())) {
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
                            .last("limit 1"));
            if (cc == null) {
                cc = new GbCascadeChannel();
                cc.setCascadeId(cascadeId);
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
    private CatalogItem toCatalogItem(GbChannel c) {
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
                .build();
    }

    private Gb28181SipClient requireClient() {
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            throw BizException.badRequest("SIP 信令服务未启动，无法推送目录");
        }
        return client;
    }

    private static String parseChannelId(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        String v = item.trim();
        int colon = v.indexOf(':');
        // 格式为 serial:code，取 code；只有一段时视为通道编号
        return colon > 0 && colon + 1 < v.length() ? v.substring(colon + 1) : v;
    }
}
