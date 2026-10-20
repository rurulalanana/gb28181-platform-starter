package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.common.CharsetFix;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbPositionLog;
import io.github.rurulalanana.starter.entity.GbPreset;
import io.github.rurulalanana.starter.entity.GbStatusLog;
import io.github.rurulalanana.starter.mapper.GbChannelMapper;
import io.github.rurulalanana.starter.mapper.GbDeviceMapper;
import io.github.rurulalanana.starter.mapper.GbPositionLogMapper;
import io.github.rurulalanana.starter.mapper.GbPresetMapper;
import io.github.rurulalanana.starter.mapper.GbStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 设备 / 通道的持久化与状态维护。
 *
 * <p>职责：
 * <ul>
 *   <li>把 SIP 侧的注册、心跳、目录应答落库（{@code gb_device} / {@code gb_channel}）</li>
 *   <li>把 ZLM 侧的流上下线映射到通道的 {@code status} / {@code streamId}</li>
 *   <li>提供在线统计等聚合查询</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final GbDeviceMapper deviceMapper;
    private final GbChannelMapper channelMapper;
    private final GbStatusLogMapper statusLogMapper;
    private final GbPositionLogMapper positionLogMapper;
    private final GbPresetMapper presetMapper;

    // ==================== 查询 ====================

    /** 全部设备。 */
    public List<GbDevice> allDevices() {
        return deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                .orderByAsc(GbDevice::getDeviceId));
    }

    /** 按设备编号查询。 */
    public GbDevice findDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return deviceMapper.selectOne(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getDeviceId, deviceId).last("limit 1"));
    }

    /** 全部通道。 */
    public List<GbChannel> allChannels() {
        return channelMapper.selectList(Wrappers.<GbChannel>lambdaQuery()
                .orderByAsc(GbChannel::getDeviceId)
                .orderByAsc(GbChannel::getChannelNo));
    }

    /**
     * 按通道编号查询。
     *
     * <p><b>注意</b>：通道编号只在单设备内唯一，多设备环境请用
     * {@link #findChannel(String, String)} 指定设备编码，否则可能拿到别的设备的同号通道。
     */
    public GbChannel findChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        return channelMapper.selectOne(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getChannelId, channelId).last("limit 1"));
    }

    /**
     * 按「设备编码 + 通道编码」查询通道（推荐）。
     *
     * <p>通道编码跨设备可重复（GB/T 28181 只保证设备编码全局唯一），
     * 只按通道编码查会随机命中别的设备的同号通道。
     */
    public GbChannel findChannel(String deviceId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        if (deviceId == null || deviceId.isBlank()) {
            return findChannel(channelId);
        }
        return channelMapper.selectOne(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .eq(GbChannel::getChannelId, channelId).last("limit 1"));
    }

    /**
     * 由通道编码反查其所属设备编码。
     *
     * <p><b>为什么需要</b>：GB/T 28181 的控制类命令（云台、预置位、录像、布防…）
     * 的 {@code <DeviceID>} 是<b>通道编码</b>，而 SIP 的 {@code To}/{@code From}/
     * 请求行必须是<b>设备编码</b>（设备才是注册实体）。业务侧接口只拿到通道编码，
     * 必须经此反查才能正确寻址。
     *
     * <p>直连摄像机两者恰好相同（设备自身即通道），下级平台接入时则不同——
     * 少了这一步，级联通道的所有控制命令都会报「设备未注册或会话已失效」。
     *
     * @return 所属设备编码；通道不存在时返回 {@code null}
     */
    public String deviceIdOfChannel(String channelId) {
        GbChannel channel = findChannel(channelId);
        return channel == null ? null : channel.getDeviceId();
    }

    /** 某设备下的通道。 */
    public List<GbChannel> channelsOf(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        return channelMapper.selectList(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .orderByAsc(GbChannel::getChannelNo));
    }

    /**
     * 定位一路「可点播通道」。
     *
     * <p>国标设备的点播对象可能是设备本身（直连摄像机，设备编号即通道编号），
     * 也可能是其下挂通道，故此处按「通道 → 设备」顺序回退查找。
     */
    public GbChannel resolveChannel(String serial, String code) {
        String id = (code == null || code.isBlank()) ? serial : code;
        GbChannel channel = findChannel(id);
        if (channel != null) {
            return channel;
        }
        // 设备直连场景：把设备自身当作通道
        GbDevice device = findDevice(id);
        if (device == null) {
            return null;
        }
        GbChannel virtual = new GbChannel();
        virtual.setChannelId(device.getDeviceId());
        virtual.setDeviceId(device.getDeviceId());
        virtual.setName(device.displayName());
        virtual.setStatus(device.getStatus());
        virtual.setPtzType(device.getPtzType());
        virtual.setChannelNo(channelNoOf(device.getDeviceId()));
        return virtual;
    }

    /** 设备总数 / 在线数。 */
    public long[] deviceStats() {
        long total = deviceMapper.selectCount(null);
        long online = deviceMapper.selectCount(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getOnline, true));
        return new long[]{total, online};
    }

    /** 通道总数 / 在线数。 */
    public long[] channelStats() {
        long total = channelMapper.selectCount(null);
        long online = channelMapper.selectCount(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getStatus, "ON"));
        return new long[]{total, online};
    }

    // ==================== 注册 / 心跳 ====================

    /** 设备注册或续注册。 */
    @Transactional
    public void onRegister(RegisterEvent event) {
        String deviceId = event.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        boolean unregister = event.isUnregistered();
        GbDevice device = findDevice(deviceId);
        String oldStatus = device == null ? null : device.getStatus();
        if (device == null) {
            device = new GbDevice();
            device.setDeviceId(deviceId);
            device.setName(deviceId);
            device.setSource("device");
            device.setCreateTime(LocalDateTime.now());
        }
        if (event.getPeer() != null) {
            device.setRemoteIp(event.getPeer().getIp());
            device.setRemotePort(event.getPeer().getPort());
        }
        device.setTransport(event.getTransport());
        device.setStatus(unregister ? "OFF" : "ON");
        device.setOnline(!unregister);
        device.setRegisterTime(LocalDateTime.now());
        if (unregister) {
            device.setKeepaliveTime(null);
        } else {
            // 注册本身就是设备存活的证据，必须一并刷新心跳时间。
            // 否则会出现「刚注册就被判离线」：设备重启后只发 REGISTER 不发心跳，
            // 而库里的 keepalive_time 还停在上次断线前，巡检一跑就按超时下线
            // （表现为设备能收下行 MESSAGE，但点播报「设备不在线」）。
            device.setKeepaliveTime(LocalDateTime.now());
        }
        device.setUpdateTime(LocalDateTime.now());

        if (device.getId() == null) {
            deviceMapper.insert(device);
        } else {
            deviceMapper.updateById(device);
        }
        if (!unregister) {
            touchDeviceChannels(deviceId, "ON");
        } else {
            touchDeviceChannels(deviceId, "OFF");
        }
        logStatus(deviceId, null, oldStatus, device.getStatus(),
                unregister ? "设备注销" : "设备注册", null);
    }

    /** 设备心跳。 */
    @Transactional
    public void onKeepAlive(KeepAliveEvent event) {
        String deviceId = event.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        GbDevice device = findDevice(deviceId);
        if (device == null) {
            // 心跳先于注册到达（平台重启后设备不会重新 REGISTER，只会继续心跳）：
            // 直接建一条完整记录，地址、注册时间、心跳时间都要一并写入，
            // 否则这些字段会永远为空——设备不会再发 REGISTER 来补。
            device = new GbDevice();
            device.setDeviceId(deviceId);
            device.setName(deviceId);
            device.setSource("device");
            device.setCreateTime(LocalDateTime.now());
            device.setStatus("ON");
            device.setOnline(true);
            device.setRegisterTime(LocalDateTime.now());
            device.setKeepaliveTime(LocalDateTime.now());
            device.setUpdateTime(LocalDateTime.now());
            if (event.getPeer() != null) {
                device.setRemoteIp(event.getPeer().getIp());
                device.setRemotePort(event.getPeer().getPort());
                if (event.getPeer().getTransport() != null) {
                    device.setTransport(event.getPeer().getTransport());
                }
            }
            deviceMapper.insert(device);
            return;
        }
        // 地址可能因设备换网而变化，每次心跳都刷新（比注册时更新鲜）
        if (event.getPeer() != null) {
            device.setRemoteIp(event.getPeer().getIp());
            device.setRemotePort(event.getPeer().getPort());
            if (event.getPeer().getTransport() != null) {
                device.setTransport(event.getPeer().getTransport());
            }
        }
        // 平台重启后设备不会重新 REGISTER，register_time 会一直是空；
        // 用首次心跳补上，保证「注册时间」字段有值
        if (device.getRegisterTime() == null) {
            device.setRegisterTime(LocalDateTime.now());
        }
        device.setKeepaliveTime(LocalDateTime.now());
        if (!Boolean.TRUE.equals(device.getOnline())) {
            device.setOnline(true);
            device.setStatus("ON");
            touchDeviceChannels(deviceId, "ON");
        }
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    /** 心跳超时下线（由定时任务调用）。 */
    @Transactional
    public int offlineSilentDevices(int timeoutSeconds) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(timeoutSeconds);
        List<GbDevice> silent = deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getOnline, true)
                .and(w -> w.isNull(GbDevice::getKeepaliveTime)
                        .or().lt(GbDevice::getKeepaliveTime, deadline)));
        if (silent.isEmpty()) {
            return 0;
        }
        // 千台规模下逐台 updateById + touchDeviceChannels + logStatus 是数千次数据库往返，
        // 事务会一直挂着（实测能把落库线程拖到「看起来卡死」）。这里改成三条批量 SQL。
        List<String> deviceIds = silent.stream().map(GbDevice::getDeviceId).toList();
        deviceMapper.markOffline(deviceIds);
        channelMapper.offlineByDeviceIds(deviceIds);
        LocalDateTime now = LocalDateTime.now();
        statusLogMapper.insertBatch(silent.stream().map(d -> {
            GbStatusLog entry = new GbStatusLog();
            entry.setDeviceId(d.getDeviceId());
            entry.setOldStatus("ON");
            entry.setNewStatus("OFF");
            entry.setDescription("心跳超时");
            entry.setReason("silent");
            entry.setCreateTime(now);
            return entry;
        }).toList());
        log.info("心跳超时下线 {} 个设备（阈值 {}s）", silent.size(), timeoutSeconds);
        return silent.size();
    }

    /** 设备是否在线（以库为准，兼容仅注册未建记录的设备）。 */
    public boolean isDeviceOnline(String deviceId) {
        GbDevice d = findDevice(deviceId);
        return d != null && Boolean.TRUE.equals(d.getOnline());
    }

    /** 通道是否在线。 */
    public boolean isChannelOnline(String channelId) {
        GbChannel c = findChannel(channelId);
        if (c != null) {
            return c.isOnline();
        }
        return isDeviceOnline(channelId);
    }

    // ==================== 目录 ====================

    /**
     * 处理设备目录应答，落库设备与通道。
     *
     * <p><b>通道只由目录应答产生</b>：设备报了什么就存什么，没报就不存。
     * 不做「等几秒没子通道就把设备自身登记为通道」的定时兜底 —— 目录应答是异步的，
     * 兜底一旦抢跑就会和设备随后上报的真实子通道并存，留下两条脏数据。
     *
     * <p><b>例外：直连摄像机（IPC）</b>。这类设备的目录应答里只有一条，
     * 且其 {@code DeviceID} 就是设备自身编号（IPC 没有下级通道，设备编号即通道编号）。
     * 若按「自身那条只当设备属性」处理，界面上会一个通道都没有、也无法点播。
     * 故当【本次响应只有自身这一条】且【库里还没有真实子通道】时，
     * 把它同时登记为通道；设备一旦报出真实子通道，这条自身通道会被清理。
     *
     * @param parentDeviceId 应答来源设备（目录项归属该设备）
     * @param items          目录项
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items) {
        // 兼容旧调用（增量/未如总量）：不做旧通道清理
        saveCatalog(parentDeviceId, items, false);
    }

    /**
     * 处理设备目录应答（带全量快照标记）。
     *
     * <p><b>{@code fullSnapshot} 的作用</b>：下级平台删掉通道后，新目录里不再包含它，
     * 但库里那条记录不会被自动删——设备列表的「通道数」会一直偏大，
     * 点播该通道也只会得到「设备未注册」的错。
     * 只有在<b>确认收齐</b>（已收条数 &ge; {@code SumNum}）时才能把它当全量快照，
     * 否则分页中途就会把还没收到的通道误删。
     *
     * @param parentDeviceId 应答来源设备
     * @param items          聚合后的目录项
     * @param fullSnapshot   是否为完整的全量目录（收齐且带了 SumNum）
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items,
                            boolean fullSnapshot) {
        saveCatalog(parentDeviceId, items, fullSnapshot, 0, 0);
    }

    /**
     * 处理设备目录应答（带全量快照标记与下级自报信息）。
     *
     * @param parentDeviceId 应答来源设备
     * @param items          聚合后的目录项
     * @param fullSnapshot   是否为完整的全量目录（收齐且带了 SumNum）
     * @param sumNum         下级自报的总条数（{@code SumNum}），0 表示未提供；
     *                       仅用于日志——出现「下级说有 N 个，库里只有 M 个」时能直接定位责任方
     * @param frames         本次批次收到的应答帧数，仅用于日志
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items,
                            boolean fullSnapshot, int sumNum, int frames) {
        if (items == null || items.isEmpty()) {
            // 空的全量快照 = 下级把所有通道都删了（应答 SumNum=0 + 空 DeviceList）。
            // 不能直接 return，否则幽灵通道永远清不掉。
            if (fullSnapshot) {
                int removed = removeChannelsNotIn(parentDeviceId, java.util.Set.of());
                int channelCount = countChannels(parentDeviceId);
                GbDevice d = findDevice(parentDeviceId);
                if (d != null) {
                    d.setChannelCount(channelCount);
                    d.setUpdateTime(LocalDateTime.now());
                    deviceMapper.updateById(d);
                }
                log.info("目录已同步: device={} 下级自报={} 收到帧数={} 本次目录项=0 通道总数={} 全量=true（空目录，已清理 {} 个）",
                        parentDeviceId, sumNum > 0 ? sumNum : "未提供", frames, channelCount, removed);
            }
            return;
        }
        GbDevice device = findDevice(parentDeviceId);
        if (device == null) {
            device = new GbDevice();
            device.setDeviceId(parentDeviceId);
            device.setName(parentDeviceId);
            device.setSource("device");
            device.setCreateTime(LocalDateTime.now());
            deviceMapper.insert(device);
        }
        CatalogItem selfItem = null;
        boolean hasSubItem = false;
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (CatalogItem item : items) {
            if (item == null || item.getDeviceId() == null || item.getDeviceId().isBlank()) {
                continue;
            }
            reported.add(item.getDeviceId());
            if (item.getDeviceId().equals(parentDeviceId)) {
                selfItem = item;
                continue;
            }
            hasSubItem = true;
            saveChannel(device, item, false);
        }
        if (selfItem != null) {
            applyDeviceInfo(device, selfItem);
        }
        if (hasSubItem) {
            // 设备确实有下级通道 → 清掉可能残留的「自身通道」
            removeSelfChannel(parentDeviceId);
            if (fullSnapshot) {
                // 收齐了：设备本次没报的通道说明已不存在，同步删掉
                int removed = removeChannelsNotIn(parentDeviceId, reported);
                if (removed > 0) {
                    log.info("目录全量核对：设备 {} 删除已下线通道 {} 个", parentDeviceId, removed);
                }
            }
        } else if (selfItem != null && !hasSubChannels(parentDeviceId)) {
            // 直连摄像机：设备自身就是唯一通道
            saveChannel(device, selfItem, true);
        }
        // 通道数按【库里的实际行数】回填，不用本次目录项计数：
        // 目录应答可能是分页/增量的（一次只报部分通道），累加会得到偏小的值。
        // 早先直接用 channelCount++ 的结果覆盖，遇到「先 DeviceInfo 报 Channel=1、
        // 后 Catalog 报 3 条」就会停在 1，与 gb_channel 的行数对不上。
        int channelCount = countChannels(parentDeviceId);
        device.setChannelCount(channelCount);
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        log.info("目录已同步: device={} 下级自报={} 收到帧数={} 本次目录项={} 通道总数={} 全量={}",
                parentDeviceId, sumNum > 0 ? sumNum : "未提供", frames, items.size(),
                channelCount, fullSnapshot);
    }

    /**
     * 删除该设备下「本次目录未上报」的通道。
     *
     * <p>「设备自身即通道」那条不参与比较：它在有真实子通道时由
     * {@link #removeSelfChannel} 单独处理，不依赖本次是否上报。
     *
     * @return 删除行数
     */
    private int removeChannelsNotIn(String deviceId, java.util.Set<String> reported) {
        java.util.List<GbChannel> existing = channelMapper.selectList(
                Wrappers.<GbChannel>lambdaQuery().eq(GbChannel::getDeviceId, deviceId));
        java.util.List<String> stale = new java.util.ArrayList<>();
        for (GbChannel c : existing) {
            String cid = c.getChannelId();
            if (cid == null || cid.equals(deviceId) || reported.contains(cid)) {
                continue;
            }
            stale.add(cid);
        }
        if (stale.isEmpty()) {
            return 0;
        }
        return channelMapper.delete(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .in(GbChannel::getChannelId, stale));
    }

    /** 统计某设备下的通道行数（含「设备自身即通道」那条）。 */
    private int countChannels(String deviceId) {
        Long n = channelMapper.selectCount(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId));
        return n == null ? 0 : n.intValue();
    }

    /** 是否已有真实子通道（不含「设备自身即通道」那条）。 */
    private boolean hasSubChannels(String deviceId) {
        Long n = channelMapper.selectCount(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .ne(GbChannel::getChannelId, deviceId));
        return n != null && n > 0;
    }

    /** 删除「设备自身即通道」那条（设备后来报出真实子通道时清理）。 */
    private void removeSelfChannel(String deviceId) {
        channelMapper.delete(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .eq(GbChannel::getChannelId, deviceId));
    }

    /**
     * 删除下级平台已删除的通道（{@code <Event>DELETE</Event>} 推送）。
     *
     * <p>国标附录 A.2.4：下级删除通道时推送 {@code <Event>DELETE</Event>}，
     * 本级若不处理，这条通道会一直残留在库里（「幽灵通道」）：
     * 通道数偏大、点播时得到「设备未注册」、级联共享时把已下线通道推给上级。
     *
     * @param deviceId  通道所属设备
     * @param channelId 通道编码
     * @return 实际删除行数
     */
    @Transactional
    public int removeChannel(String deviceId, String channelId) {
        if (deviceId == null || deviceId.isBlank() || channelId == null || channelId.isBlank()) {
            return 0;
        }
        int removed = channelMapper.delete(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .eq(GbChannel::getChannelId, channelId));
        if (removed > 0) {
            // 通道数按库里实际行数回填
            GbDevice device = findDevice(deviceId);
            if (device != null) {
                device.setChannelCount(countChannels(deviceId));
                device.setUpdateTime(LocalDateTime.now());
                deviceMapper.updateById(device);
            }
            log.info("下级删除通道已同步: device={} channel={} 剩余通道={}",
                    deviceId, channelId, countChannels(deviceId));
        }
        return removed;
    }

    /** 处理设备信息应答（{@code DeviceInfo} 查询结果），更新设备属性。 */
    @Transactional
    public void saveDeviceInfo(String deviceId, ManscdpMessage msg) {
        GbDevice device = findDevice(deviceId);
        if (device == null) {
            return;
        }
        applyText(msg, "DeviceName", device::setName);
        applyText(msg, "Manufacturer", device::setManufacturer);
        applyText(msg, "Model", device::setModel);
        applyText(msg, "Firmware", device::setFirmware);
        applyText(msg, "CivilCode", device::setCivilCode);
        applyText(msg, "Address", device::setAddress);
        // 刻意不采纳 DeviceInfo 里的 <Channel>：那是设备自报的通道数，
        // 与平台实际同步到的 gb_channel 行数常不一致（设备少报/多报、目录尚未同步完），
        // 采纳会让「设备列表的通道数」与「通道列表的实际条数」对不上。
        // 通道数一律以 gb_channel 的实际行数为准（见 saveCatalog / countChannels）。
        applyDouble(msg, "Longitude", v -> device.setLongitude(BigDecimal.valueOf(v)));
        applyDouble(msg, "Latitude", v -> device.setLatitude(BigDecimal.valueOf(v)));
        device.setChannelCount(countChannels(deviceId));
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    /**
     * 保存通道。
     *
     * @param selfChannel true 表示这是「设备自身即通道」（直连摄像机）那条，
     *                    目录项缺 {@code Status} 时用设备状态兜底
     */
    private void saveChannel(GbDevice device, CatalogItem item, boolean selfChannel) {
        // 必须按「设备编码 + 通道编码」查：通道编码跨设备可重复，
        // 只按通道编码查会把第二台设备的同号通道当成已存在而覆盖掉
        GbChannel channel = findChannel(device.getDeviceId(), item.getDeviceId());
        if (channel == null) {
            channel = new GbChannel();
            channel.setChannelId(item.getDeviceId());
            channel.setCreateTime(LocalDateTime.now());
        }
        channel.setDeviceId(device.getDeviceId());
        channel.setName(CharsetFix.repair(item.getName()));
        channel.setChannelNo(channelNoOf(item.getDeviceId()));
        channel.setManufacturer(CharsetFix.repair(item.getManufacturer()));
        channel.setModel(CharsetFix.repair(item.getModel()));
        channel.setOwner(null);
        channel.setCivilCode(item.getCivilCode());
        channel.setBlock(CharsetFix.repair(item.getBlock()));
        channel.setAddress(CharsetFix.repair(item.getAddress()));
        channel.setParental(item.getParental());
        // ParentID 缺省时挂到所属设备下：通道树按 parentId 组装，
        // 留空会让通道在树里找不到父节点而不显示
        String parentId = item.getParentId();
        channel.setParentId(parentId == null || parentId.isBlank()
                ? device.getDeviceId() : parentId);
        channel.setRegisterWay(item.getRegisterWay());
        channel.setSecrecy(item.getSecrecy());
        channel.setIpAddress(item.getIpAddress());
        channel.setPort(item.getPort());
        // 云台类型：设备普遍不报或恒报 0（未知），而现场可能已手工在通道列表里改成
        // 球机/半球以便开启云台控制。若每次目录同步都无条件写入，
        // 手工值会在设备下一次注册（实测约 5 分钟）时被冲回 0，
        // 表现为「改完一会儿又自己变回去了」。故只在设备报出非 0 值时采纳。
        if (item.getPtzType() != null && item.getPtzType() != 0) {
            channel.setPtzType(item.getPtzType());
        } else if (channel.getId() == null) {
            // 新建通道且设备未报类型：落 0，避免 NULL 让前端判空逻辑分支变多
            channel.setPtzType(item.getPtzType() == null ? 0 : item.getPtzType());
        }
        channel.setLongitude(item.getLongitude() == null ? null : BigDecimal.valueOf(item.getLongitude()));
        channel.setLatitude(item.getLatitude() == null ? null : BigDecimal.valueOf(item.getLatitude()));
        channel.setResolution(item.getResolution());
        channel.setStreamNumberList(item.getStreamNumberList());
        channel.setDownloadSpeed(item.getDownloadSpeed());
        channel.setBusinessGroupId(item.getBusinessGroupId());
        String status = item.getStatus();
        if ((status == null || status.isBlank()) && selfChannel) {
            // 直连摄像机：自身通道的在线状态就是设备在线状态。
            // 留空会被 isOnline() 判为离线，点播直接报「通道不在线」。
            status = device.getStatus() != null ? device.getStatus()
                    : (Boolean.TRUE.equals(device.getOnline()) ? "ON" : "OFF");
        }
        channel.setStatus(status);
        channel.setUpdateTime(LocalDateTime.now());
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);
        }
    }

    /**
     * 从通道国标编号推导通道序号。
     *
     * <p>GB/T 28181—2016 6.1 规定 20 位国标编码的末 7 位为序号，
     * 设备侧习惯把其中的末 3 位当作「通道号」展示（如 {@code ...0001} → 1、
     * {@code ...0102} → 102），与 LiveGBS 等平台的显示一致。
     *
     * <p>目录应答里没有独立的「通道号」字段，早先这一列恒为 0。
     * 取不到数字时返回 0，不抛异常 —— 通道编码可能被自定义成非数字串。
     */
    private static int channelNoOf(String channelId) {
        if (channelId == null || channelId.length() < 3) {
            return 0;
        }
        String tail = channelId.substring(channelId.length() - 3);
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void applyDeviceInfo(GbDevice device, CatalogItem item) {
        if (item.getName() != null) {
            device.setName(CharsetFix.repair(item.getName()));
        }
        if (item.getManufacturer() != null) {
            device.setManufacturer(CharsetFix.repair(item.getManufacturer()));
        }
        if (item.getModel() != null) {
            device.setModel(CharsetFix.repair(item.getModel()));
        }
        if (item.getCivilCode() != null) {
            device.setCivilCode(item.getCivilCode());
        }
        if (item.getAddress() != null) {
            device.setAddress(CharsetFix.repair(item.getAddress()));
        }
        // 与通道同理：设备常恒报 0（未知），不要用它覆盖手工设置的类型
        if (item.getPtzType() != null && item.getPtzType() != 0) {
            device.setPtzType(item.getPtzType());
        }
        if (item.getLongitude() != null) {
            device.setLongitude(BigDecimal.valueOf(item.getLongitude()));
        }
        if (item.getLatitude() != null) {
            device.setLatitude(BigDecimal.valueOf(item.getLatitude()));
        }
    }

    // ==================== 通道状态 ====================

    /** 更新某通道的在线状态。 */
    @Transactional
    public void setChannelStatus(String channelId, String status) {
        GbChannel channel = findChannel(channelId);
        if (channel == null) {
            return;
        }
        String old = channel.getStatus();
        if (status.equals(old)) {
            return;
        }
        channel.setStatus(status);
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
        logStatus(channel.getDeviceId(), channelId, old, status, "通道状态变更", null);
    }

    /**
     * 更新通道状态（按「设备编码 + 通道编码」精确定位）。
     *
     * <p>通道编码跨设备可重复，只按通道编码可能改到别的设备的同号通道。
     */
    public void setChannelStatus(String deviceId, String channelId, String status) {
        GbChannel channel = findChannel(deviceId, channelId);
        if (channel == null) {
            return;
        }
        String old = channel.getStatus();
        if (status.equals(old)) {
            return;
        }
        channel.setStatus(status);
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
        logStatus(channel.getDeviceId(), channelId, old, status, "通道状态变更", null);
    }

    /** 记录 / 清除通道的当前直播流。 */
    @Transactional
    public void setChannelStream(String channelId, String streamId) {
        GbChannel channel = findChannel(channelId);
        if (channel == null) {
            return;
        }
        if (streamId == null || streamId.isBlank()) {
            // 必须走显式 UPDATE：updateById 会跳过 null 字段，清不掉 stream_id
            channelMapper.clearStreamIdByChannel(channelId);
            return;
        }
        channel.setStreamId(streamId);
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
    }

    /**
     * 记录 / 清除通道的当前直播流（按「设备编码 + 通道编码」精确定位）。
     *
     * <p>通道编码跨设备可重复，只用通道编码可能改到别的设备的同号通道。
     */
    @Transactional
    public void setChannelStream(String deviceId, String channelId, String streamId) {
        GbChannel channel = findChannel(deviceId, channelId);
        if (channel == null) {
            return;
        }
        if (streamId == null || streamId.isBlank()) {
            // 必须走显式 UPDATE：updateById 会跳过 null 字段，清不掉 stream_id
            channelMapper.clearStreamId(deviceId, channelId);
            return;
        }
        channel.setStreamId(streamId);
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
    }

    /** 通道配置项更新（按需直播 / 云端录像 / 音频 / 分享等）。 */
    @Transactional
    public void updateChannel(GbChannel channel) {
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
    }

    /**
     * 平台启动时收敛残留的通道状态。
     *
     * <p><b>为什么必须做</b>：通道的 {@code status=ON} 与 {@code stream_id} 都由
     * 流上线事件写入，而「流下线」只在设备/流正常收敛时才触发。平台重启、
     * 进程被杀、或某个流异常终止而没有走到下线分支时，这两个字段就永久留在
     * 「在线/有流」状态 —— 界面显示通道在线、挂着 stream_id，实际一路都没有。
     * 实测踩过：一个 11:01 就离线的设备，其通道被错误认领后显示 ON 达数小时。
     *
     * <p>判据用<b>设备是否在线</b>而不是「有没有会话」：设备离线则其所有通道
     * 必然不可能有流；而设备在线时通道状态由后续真实的流事件驱动。
     * 这样既清掉脏状态，又不会误伤「设备在线但暂时没推流」的正常通道。
     *
     * @return 收敛的通道数
     */
    @Transactional
    public int resetStaleChannelStates() {
        try {
            List<GbDevice> all = allDevices();
            List<String> offlineIds = all.stream()
                    .filter(d -> !Boolean.TRUE.equals(d.getOnline()))
                    .map(GbDevice::getDeviceId)
                    .toList();
            // 离线设备的通道：状态置 OFF、清空 stream_id
            int off = channelMapper.resetByDeviceIds(offlineIds);
            // 在线设备的通道：清空 stream_id（内存会话已随重启清空，这个字段必然过期），
            // 但不动 status —— 设备在线时通道状态由真实流事件驱动
            int cleared = channelMapper.clearStreamIdsByDeviceIds(
                    all.stream().map(GbDevice::getDeviceId).toList());
            if (off > 0 || cleared > 0) {
                log.info("已收敛残留通道状态：{} 个离线设备通道置 OFF，{} 个通道清除 stream_id", off, cleared);
            }
            return off + cleared;
        } catch (Exception e) {
            log.warn("收敛残留通道状态失败（不影响启动）: {}", e.getMessage());
            return 0;
        }
    }

    /** 设备属性更新。 */
    @Transactional
    public void updateDevice(GbDevice device) {
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    /** 删除设备及其通道（仅允许离线设备，调用方需先校验）。 */
    @Transactional
    public void removeDevice(GbDevice device) {
        channelMapper.delete(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, device.getDeviceId()));
        deviceMapper.deleteById(device.getId());
        log.info("已删除设备及其通道: device={}", device.getDeviceId());
    }

    /** 保存预置位自定义名称。 */
    @Transactional
    public void savePresetName(String channelId, Integer presetId, String name) {
        if (channelId == null || presetId == null) {
            return;
        }
        GbPreset existing = presetMapper.selectOne(Wrappers.<GbPreset>lambdaQuery()
                .eq(GbPreset::getChannelId, channelId)
                .eq(GbPreset::getPresetId, presetId)
                .last("limit 1"));
        GbPreset preset = existing == null ? new GbPreset() : existing;
        preset.setChannelId(channelId);
        preset.setPresetId(presetId);
        preset.setName(name);
        preset.setUpdateTime(LocalDateTime.now());
        if (preset.getId() == null) {
            presetMapper.insert(preset);
        } else {
            presetMapper.updateById(preset);
        }
    }

    /** 查询某通道的预置位名称映射。 */
    public List<GbPreset> presetsOf(String channelId) {
        return presetMapper.selectList(Wrappers.<GbPreset>lambdaQuery()
                .eq(GbPreset::getChannelId, channelId)
                .orderByAsc(GbPreset::getPresetId));
    }

    private void touchDeviceChannels(String deviceId, String status) {
        channelMapper.updateStatusByDeviceId(deviceId, status);
    }

    // ==================== 状态日志 ====================

    /** 写一条状态变更记录。 */
    public void logStatus(String deviceId, String channelId, String oldStatus,
                          String newStatus, String description, String reason) {
        GbStatusLog logEntry = new GbStatusLog();
        logEntry.setDeviceId(deviceId);
        logEntry.setChannelId(channelId);
        logEntry.setOldStatus(oldStatus);
        logEntry.setNewStatus(newStatus);
        logEntry.setDescription(description);
        logEntry.setReason(reason);
        logEntry.setCreateTime(LocalDateTime.now());
        statusLogMapper.insert(logEntry);
    }

    /** 状态变更记录查询。 */
    public List<GbStatusLog> statusLogs(String deviceId, String channelId,
                                        String startTime, String endTime) {
        LambdaQueryWrapper<GbStatusLog> q = Wrappers.<GbStatusLog>lambdaQuery()
                .eq(deviceId != null && !deviceId.isBlank(), GbStatusLog::getDeviceId, deviceId)
                .eq(channelId != null && !channelId.isBlank(), GbStatusLog::getChannelId, channelId);
        LocalDateTime from = TimeFormats.parse(startTime);
        LocalDateTime to = TimeFormats.parse(endTime);
        if (from != null) {
            q.ge(GbStatusLog::getCreateTime, from);
        }
        if (to != null) {
            q.le(GbStatusLog::getCreateTime, to);
        }
        return statusLogMapper.selectList(q.orderByDesc(GbStatusLog::getCreateTime));
    }

    /**
     * 记录一条移动位置。
     *
     * <p>位置由设备通过 {@code MobilePosition} 主动上报，或由报警附带坐标。
     * 经纬度全为 0 的记录没有意义（设备未装 GPS），直接丢弃，
     * 否则轨迹图上会出现一堆「在几内亚湾」的点。
     */
    public void savePosition(String deviceId, String channelId, ManscdpMessage msg) {
        Double lon = firstDouble(msg, "Longitude");
        Double lat = firstDouble(msg, "Latitude");
        if (lon == null || lat == null || (lon == 0d && lat == 0d)) {
            return;
        }
        GbPositionLog entry = new GbPositionLog();
        entry.setDeviceId(deviceId);
        entry.setChannelId(channelId);
        entry.setMethod(msg.firstText("Method"));
        entry.setLongitude(BigDecimal.valueOf(lon));
        entry.setLatitude(BigDecimal.valueOf(lat));
        entry.setAltitude(decimalOf(firstDouble(msg, "Altitude")));
        entry.setSpeed(decimalOf(firstDouble(msg, "Speed")));
        entry.setDirection(decimalOf(firstDouble(msg, "Direction")));
        entry.setCreateTime(LocalDateTime.now());
        positionLogMapper.insert(entry);
    }

    /** 位置记录查询，按时间倒序。 */
    public List<GbPositionLog> positionLogs(String deviceId, String channelId,
                                             String startTime, String endTime) {
        LambdaQueryWrapper<GbPositionLog> q = Wrappers.<GbPositionLog>lambdaQuery()
                .eq(deviceId != null && !deviceId.isBlank(), GbPositionLog::getDeviceId, deviceId)
                .eq(channelId != null && !channelId.isBlank(), GbPositionLog::getChannelId, channelId);
        LocalDateTime from = TimeFormats.parse(startTime);
        LocalDateTime to = TimeFormats.parse(endTime);
        if (from != null) {
            q.ge(GbPositionLog::getCreateTime, from);
        }
        if (to != null) {
            q.le(GbPositionLog::getCreateTime, to);
        }
        return positionLogMapper.selectList(q.orderByDesc(GbPositionLog::getCreateTime));
    }

    private static BigDecimal decimalOf(Double v) {
        return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }

    private static Double firstDouble(ManscdpMessage msg, String tag) {
        String v = msg.firstText(tag);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 内部：MANSCDP 取值 ====================

    private static void applyText(ManscdpMessage msg, String tag, java.util.function.Consumer<String> setter) {
        String v = msg.firstText(tag);
        if (v != null && !v.isBlank()) {
            // 设备普遍以 GBK 字节发中文，而解码器仅在出现替换字符时才回退 GBK，
            // 遇到「GBK 字节恰为合法 UTF-8」的情况会静默产出乱码（如「平台」→「ƽ̨」）
            setter.accept(CharsetFix.repair(v));
        }
    }

    private static void applyDouble(ManscdpMessage msg, String tag,
                                    java.util.function.Consumer<Double> setter) {
        String v = msg.firstText(tag);
        if (v == null || v.isBlank()) {
            return;
        }
        try {
            setter.accept(Double.valueOf(v));
        } catch (NumberFormatException ignore) {
            // 设备上报的坐标非数值，忽略
        }
    }

    /** 便捷：从 MANSCDP 的 {@code <Item>} 元素取文本。 */
    public static String textOf(Element element, String tag) {
        return Optional.ofNullable(element)
                .map(e -> e.getElementsByTagName(tag))
                .filter(n -> n.getLength() > 0)
                .map(n -> n.item(0).getTextContent())
                .orElse(null);
    }

    /** 全部设备编号（供批量查询使用）。 */
    public List<String> allDeviceIds() {
        List<String> ids = new ArrayList<>();
        for (GbDevice d : allDevices()) {
            ids.add(d.getDeviceId());
        }
        return ids;
    }

    /** SIP 注册表中的会话（在线设备）。 */
    public List<DeviceSession> sipSessions(Collection<DeviceSession> sessions) {
        return new ArrayList<>(sessions);
    }
}
