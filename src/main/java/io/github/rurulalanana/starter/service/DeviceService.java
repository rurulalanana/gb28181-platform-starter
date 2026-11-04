package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.common.CharsetFix;
import io.github.rurulalanana.starter.common.Numbers;
import io.github.rurulalanana.starter.common.Queries;
import io.github.rurulalanana.starter.dto.DeviceRef;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
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
 * 设备 / 通道的持久化与状态维护：SIP 注册 / 心跳 / 目录应答落库，
 * ZLM 流上下线映射到通道的 {@code status} / {@code streamId}，以及在线统计等聚合查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final GbDeviceMapper deviceMapper;
    private final GbChannelMapper channelMapper;
    private final GbStatusLogMapper statusLogMapper;
    private final GbPositionLogMapper positionLogMapper;
    /** 设备侧录像索引（仅用于过期清理；正式读写都在 RecordService）。 */
    private final io.github.rurulalanana.starter.mapper.GbDeviceRecordMapper deviceRecordMapper;
    private final GbPresetMapper presetMapper;

    // ==================== 查询 ====================

    public List<GbDevice> allDevices() {
        return deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                .orderByAsc(GbDevice::getDeviceId));
    }

    public GbDevice findDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return deviceMapper.selectOne(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getDeviceId, deviceId).last("limit 1"));
    }

    /**
     * 批量查设备名 —— 给「列表里每条都要显示设备名」的场景用。
     *
     * <p><b>为什么必须有这个方法</b>：列表逐条调 {@link #findDevice} 是典型 N+1。
     * 实测报警列表 5819 条 × 2 次（设备名 + 通道名）= 11638 次跨网查询，
     * 单次约 25ms → 整个接口 290 秒，页面直接超时打不开。
     * 一次 {@code IN} 查询把整页需要的名字全取回来。
     *
     * @param deviceIds 设备编号集合（允许有空值/重复，内部去重）
     * @return 设备编号 → 显示名；查不到的键不会出现在 Map 里
     */
    public java.util.Map<String, String> deviceNames(Collection<String> deviceIds) {
        java.util.Set<String> ids = cleanIds(deviceIds);
        if (ids.isEmpty()) {
            // 必须用 emptyMap 而不是 Map.of()：报警行的 deviceId/channelId 可以是 null
            // （SDK 上报的事件没有通道号），而不可变 Map 的 get(null) 会抛 NPE。
            return java.util.Collections.emptyMap();
        }
        java.util.Map<String, String> out = new java.util.HashMap<>(ids.size() * 2);
        for (GbDevice d : deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                .in(GbDevice::getDeviceId, ids))) {
            if (d != null && d.getDeviceId() != null) {
                out.put(d.getDeviceId(), d.displayName());
            }
        }
        return out;
    }

    /** 批量查通道名，理由同 {@link #deviceNames}。 */
    public java.util.Map<String, String> channelNames(Collection<String> channelIds) {
        java.util.Set<String> ids = cleanIds(channelIds);
        if (ids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<String, String> out = new java.util.HashMap<>(ids.size() * 2);
        for (GbChannel c : channelMapper.selectList(Wrappers.<GbChannel>lambdaQuery()
                .in(GbChannel::getChannelId, ids))) {
            if (c != null && c.getChannelId() != null) {
                // 同一通道编码可能出现在多台设备下：保留先到的那条，列表里只是展示用
                out.putIfAbsent(c.getChannelId(), c.displayName());
            }
        }
        return out;
    }

    private static java.util.Set<String> cleanIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>(ids.size() * 2);
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 按接入方式列设备。
     *
     * <p>判据写成 SQL 而不是在 Java 里过滤 {@code DeviceKind.of}：后者要遍历全表，
     * 千台规模下每次调用都是一次全表扫描 + 对象构造。
     *
     * <p>{@code source} 的历史值很杂（{@code device} / {@code null} / {@code gb}），
     * 所以这里用「明确属于 SDK 的值」正面匹配，而不是「不等于 gb28181」反面匹配 ——
     * 反面匹配会把历史国标设备误判成 SDK 设备。
     *
     * @param source 接入方式取值（{@code DeviceKind#code()}}）
     */
    public List<GbDevice> findBySource(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        // netsdk 是 dahua 的历史别名（DeviceKind.of 里两者等价），一起匹配
        return deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                .in(GbDevice::getSource, source, "netsdk"));
    }

    /**
     * 设备是否在线。集中在一处，使「设备在线」的判定口径只有一份。
     *
     * @return 设备不存在返回 {@code false}
     */
    public boolean deviceOnline(String deviceId) {
        GbDevice device = findDevice(deviceId);
        return device != null && Boolean.TRUE.equals(device.getOnline());
    }

    public List<GbChannel> allChannels() {
        return channelMapper.selectList(Wrappers.<GbChannel>lambdaQuery()
                .orderByAsc(GbChannel::getDeviceId)
                .orderByAsc(GbChannel::getChannelNo));
    }

    /**
     * 按通道编号查询。通道编号只在单设备内唯一，多设备环境请用
     * {@link #findChannel(String, String)}，否则可能拿到别的设备的同号通道。
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
     * 通道编码跨设备可重复（GB/T 28181 只保证设备编码全局唯一），只按通道编码查会随机命中别的设备。
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
     * 由通道编码反查其所属设备编码：控制类命令的 {@code <DeviceID>} 是通道编码，
     * 而 SIP 的 {@code To}/{@code From}/请求行必须是设备编码。直连摄像机二者相同，级联则不同。
     */
    public String deviceIdOfChannel(String channelId) {
        GbChannel channel = findChannel(channelId);
        return channel == null ? null : channel.getDeviceId();
    }

    public List<GbChannel> channelsOf(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        return channelMapper.selectList(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId)
                .orderByAsc(GbChannel::getChannelNo));
    }

    /**
     * 定位一路可点播通道：先按通道查，查不到再回退到把设备自身当通道（直连摄像机）。
     */
    public GbChannel resolveChannel(String serial, String code) {
        String id = DeviceRef.channelOf(serial, code);
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

    public long[] deviceStats() {
        long total = deviceMapper.selectCount(null);
        long online = deviceMapper.selectCount(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getOnline, true));
        return new long[]{total, online};
    }

    public long[] channelStats() {
        long total = channelMapper.selectCount(null);
        long online = channelMapper.selectCount(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getStatus, "ON"));
        return new long[]{total, online};
    }

    // ==================== 注册 / 心跳 ====================

    /**
     * 未知设备是否可以落库。
     * SIP 端口开在公网后扫描器会不停发 REGISTER（实测一天几十次），用的是随手编的编号
     * （{@code .} / {@code 059} / {@code 9999}）；照单全收会让设备列表混进指向公网 IP 的假设备，
     * 目录采集还会主动向这些 IP 发 MESSAGE，每台每次等满 32 秒事务超时。
     * 判据用 GB/T 28181 附录 E 的 20 位编码规则；只拦新建，库里已有记录一律放行
     * （手工添加的自定义编号不会被锁死）。
     */
    private boolean mayCreate(String deviceId, GbDevice existing) {
        if (existing != null) {
            return true;
        }
        boolean valid = io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(deviceId);
        if (!valid) {
            log.debug("忽略非法设备编码的注册/心跳（公网扫描器常见）: {}", deviceId);
        }
        return valid;
    }

    @Transactional
    public void onRegister(RegisterEvent event) {
        String deviceId = event.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        boolean unregister = event.isUnregistered();
        GbDevice device = findDevice(deviceId);
        if (!mayCreate(deviceId, device)) {
            return;
        }
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
            // 注册本身就是设备存活的证据，必须一并刷新心跳时间；否则设备重启后只发 REGISTER
            // 不发心跳，库里的 keepalive_time 还停在断线前，巡检一跑就按超时下线。
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

    @Transactional
    public void onKeepAlive(KeepAliveEvent event) {
        String deviceId = event.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        GbDevice device = findDevice(deviceId);
        if (!mayCreate(deviceId, device)) {
            return;
        }
        if (device == null) {
            // 心跳先于注册到达（平台重启后设备不会重新 REGISTER，只会继续心跳）：
            // 直接建完整记录，地址 / 注册时间 / 心跳时间一并写入，否则这些字段永远为空。
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
        // 平台重启后设备不会重新 REGISTER，register_time 会一直是空；用首次心跳补上
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
                /*
                 * 只对「靠设备主动发心跳续命」的接入方式做静默判定。
                 *
                 * <p><b>SDK 直连设备（大华等）根本不发心跳</b>：它们只在注册/登录时
                 * 更新一次 keepalive_time，之后无论设备多健康，这个时间都会一直变旧。
                 * 不排除的话，设备接入 3 分钟后必然被判"心跳超时"下线，
                 * 通道跟着变 OFF，点播直接报「通道不在线」—— 而设备其实完全正常。
                 * 这类设备只能靠主动探测（见 PlatformScheduler#probeSdkDevices）。
                 *
                 * <p>判据与 {@code DeviceKind.of} 保持一致：只有 dahua / netsdk
                 * 属于无心跳的直连接入，其余（含历史值 device、gb、sip、null）都按国标处理。
                 */
                .apply("lower(trim(coalesce(source, ''))) not in ('dahua', 'netsdk')")
                .and(w -> w.isNull(GbDevice::getKeepaliveTime)
                        .or().lt(GbDevice::getKeepaliveTime, deadline)));
        if (silent.isEmpty()) {
            return 0;
        }
        // 千台规模下逐台 updateById + touchDeviceChannels + logStatus 是数千次数据库往返，
        // 事务会一直挂着（实测能把落库线程拖到「看起来卡死」），故改成三条批量 SQL。
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
     * 通道只由目录应答产生：设备报了什么就存什么，不做「等几秒没子通道就把设备自身登记为通道」
     * 的定时兜底 —— 目录应答是异步的，兜底抢跑会与随后上报的子通道并存，留下两条脏数据。
     * 例外是直连摄像机（IPC）：目录只有一条且其 {@code DeviceID} 就是设备自身编号，
     * 当「只有自身这一条」且「库里还没有真实子通道」时也登记为通道，设备报出子通道后自动清理。
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items) {
        // 兼容旧调用（增量/未如总量）：不做旧通道清理
        saveCatalog(parentDeviceId, items, false);
    }

    /**
     * 处理设备目录应答（带全量快照标记）。
     * {@code fullSnapshot} 用于删掉下级已删除的通道：只有确认收齐（已收条数 &ge; {@code SumNum}）
     * 才能当全量快照，否则分页中途会把还没收到的通道误删。
     *
     * @param fullSnapshot 是否为完整的全量目录（收齐且带了 SumNum）
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items,
                            boolean fullSnapshot) {
        saveCatalog(parentDeviceId, items, fullSnapshot, 0, 0);
    }

    /**
     * 处理设备目录应答（带全量快照标记与下级自报信息）。
     *
     * @param sumNum 下级自报的 {@code SumNum}，0 表示未提供；仅用于日志定位「下级说 N 个、库里 M 个」
     * @param frames 本批次收到的应答帧数，仅用于日志
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
        // 通道数按库里的实际行数回填，不用本次目录项计数：目录可能是分页 / 增量的，
        // 累加会偏小（实测先 DeviceInfo 报 Channel=1、后 Catalog 报 3 条，结果停在 1）。
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
     * 「设备自身即通道」那条不参与比较，它由 {@link #removeSelfChannel} 单独处理。
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
     * 国标附录 A.2.4：不处理会留下幽灵通道 —— 通道数偏大、点播报「设备未注册」、
     * 级联共享时把已下线通道推给上级。
     *
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
        // 刻意不采纳 DeviceInfo 里的 <Channel>：设备自报的通道数与平台实际同步到的
        // gb_channel 行数常不一致，采纳会让「设备列表通道数」与「通道列表条数」对不上。
        applyDouble(msg, "Longitude", v -> device.setLongitude(BigDecimal.valueOf(v)));
        applyDouble(msg, "Latitude", v -> device.setLatitude(BigDecimal.valueOf(v)));
        device.setChannelCount(countChannels(deviceId));
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    /**
     * 保存通道。
     *
     * @param selfChannel true 表示「设备自身即通道」（直连摄像机），目录项缺 {@code Status} 时用设备状态兜底
     */
    private void saveChannel(GbDevice device, CatalogItem item, boolean selfChannel) {
        // 必须按「设备编码 + 通道编码」查：只按通道编码查会把第二台设备的同号通道当成已存在而覆盖掉
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
        // ParentID 缺省时挂到所属设备下：通道树按 parentId 组装，留空会找不到父节点而不显示
        String parentId = item.getParentId();
        channel.setParentId(parentId == null || parentId.isBlank()
                ? device.getDeviceId() : parentId);
        channel.setRegisterWay(item.getRegisterWay());
        channel.setSecrecy(item.getSecrecy());
        channel.setIpAddress(item.getIpAddress());
        channel.setPort(item.getPort());
        // 云台类型：设备普遍不报或恒报 0，而现场可能已手工改成球机 / 半球以便开启云台控制。
        // 无条件写入会在下次目录同步（实测约 5 分钟）时把手值冲回 0，故只在设备报非 0 值时采纳。
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
     * 从通道国标编号推导通道序号：GB/T 28181—2016 6.1 规定 20 位编码末 7 位为序号，
     * 设备侧惯例取末 3 位展示（{@code ...0001} → 1、{@code ...0102} → 102），与 LiveGBS 一致。
     * 目录应答没有独立的通道号字段；取不到数字返回 0，不抛异常（编号可能被自定义成非数字串）。
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
        // 与通道同理：设备常恒报 0，不要用它覆盖手工设置的类型
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
     * 通道编码跨设备可重复，只按通道编码可能改到别的设备的同号通道。
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
     * 通道编码跨设备可重复，只用通道编码可能改到别的设备的同号通道。
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
     * 单独设置通道的自定义国标编号，{@code null}/空串表示清除。
     * 不能复用 {@link #updateChannel}：{@code updateById} 的 {@code FieldStrategy.NOT_NULL}
     * 会跳过 null 字段，清空永远不生效（接口 200、界面提示成功，库里还是旧值）。
     * 定位必须用「设备 + 通道」：通道编码只在单设备内唯一。
     *
     * @return 是否命中并更新了记录
     */
    public boolean setChannelCustomId(String deviceId, String channelId, String customId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        String value = customId == null || customId.isBlank() ? null : customId.trim();
        int rows = channelMapper.update(null, Wrappers.<GbChannel>lambdaUpdate()
                .eq(GbChannel::getDeviceId, deviceId)
                .eq(GbChannel::getChannelId, channelId)
                .set(GbChannel::getCustomId, value)
                .set(GbChannel::getUpdateTime, LocalDateTime.now()));
        return rows > 0;
    }

    /**
     * 平台启动时收敛残留的通道状态：{@code status=ON} 与 {@code stream_id} 由流上线事件写入，
     * 而「流下线」只在正常收敛时触发，重启 / 进程被杀会让字段永久留在「在线有流」（实测数小时）。
     * 设备离线则通道必然没流；设备在线时交给后续真实流事件。
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
            // 离线设备的通道置 OFF 并清空 stream_id；在线设备只清 stream_id
            // （内存会话已随重启清空，该字段必然过期），不动 status
            int off = channelMapper.resetByDeviceIds(offlineIds);
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

    /**
     * 保存预置位自定义名称。
     *
     * @param name 名称；空或 {@code null} 表示清除（删除预置位时用）
     */
    @Transactional
    public void savePresetName(String channelId, Integer presetId, String name) {
        if (channelId == null || presetId == null) {
            return;
        }
        /* 清除走 delete 而不是把 name 更新成 null：MyBatis-Plus 的 FieldStrategy.NOT_NULL
           会让 null 不进 SQL（与 setChannelCustomId 同一个坑）。名称表本来就是有自定义名才有行。 */
        if (name == null || name.isBlank()) {
            presetMapper.delete(Wrappers.<GbPreset>lambdaQuery()
                    .eq(GbPreset::getChannelId, channelId)
                    .eq(GbPreset::getPresetId, presetId));
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

    public List<GbPreset> presetsOf(String channelId) {
        return presetMapper.selectList(Wrappers.<GbPreset>lambdaQuery()
                .eq(GbPreset::getChannelId, channelId)
                .orderByAsc(GbPreset::getPresetId));
    }


    private void touchDeviceChannels(String deviceId, String status) {
        channelMapper.updateStatusByDeviceId(deviceId, status);
    }

    /**
     * 把设备与其全部通道一并置为指定在线状态（供主动探测类接入使用）。
     *
     * <p>SDK 直连设备没有 SIP 心跳，在线状态由探测结果决定，
     * 通道状态必须跟着设备一起翻 —— 只改设备不改通道，
     * 界面会显示「设备在线、通道离线」，点播仍报「通道不在线」。
     */
    @Transactional
    public void setDeviceAndChannelsStatus(String deviceId, boolean online) {
        String status = online ? "ON" : "OFF";
        deviceMapper.update(null, Wrappers.<GbDevice>lambdaUpdate()
                .eq(GbDevice::getDeviceId, deviceId)
                .set(GbDevice::getOnline, online)
                .set(GbDevice::getStatus, status)
                .set(GbDevice::getUpdateTime, LocalDateTime.now())
                .set(online, GbDevice::getKeepaliveTime, LocalDateTime.now()));
        touchDeviceChannels(deviceId, status);
    }

    // ==================== 状态日志 ====================

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

    /**
     * 清理超过保留期的状态变更记录（单条 {@code DELETE ... WHERE create_time < ?}，不先 select 再删）。
     *
     * @param retainDays 保留天数；{@code <=0} 直接跳过
     * @return 删除的行数
     */
    public int purgeStatusLogs(int retainDays) {
        if (retainDays <= 0) {
            return 0;
        }
        return statusLogMapper.delete(Wrappers.<GbStatusLog>lambdaQuery()
                .lt(GbStatusLog::getCreateTime, LocalDateTime.now().minusDays(retainDays)));
    }

    /**
     * 移动位置记录过期清理：车载 / 单兵设备一天数百到上万条，此前无任何清理路径。
     * 轨迹只需回溯最近一周。
     *
     * @param retainDays 保留天数；{@code <=0} 直接跳过
     * @return 删除的行数
     */
    public int purgePositionLogs(int retainDays) {
        if (retainDays <= 0) {
            return 0;
        }
        return positionLogMapper.delete(Wrappers.<GbPositionLog>lambdaQuery()
                .lt(GbPositionLog::getCreateTime, LocalDateTime.now().minusDays(retainDays)));
    }

    /**
     * 设备侧录像索引（{@code gb_device_record}）过期清理：它只是检索结果的缓存，
     * 真实清单在设备上随时可重新检索，所以保留期最短。
     *
     * @param retainDays 保留天数；{@code <=0} 直接跳过
     * @return 删除的行数
     */
    public int purgeDeviceRecords(int retainDays) {
        if (retainDays <= 0) {
            return 0;
        }
        // 用 updateTime 而不是 createTime：这张表没有 createTime 列，重新检索会 upsert 并刷新 updateTime
        return deviceRecordMapper.delete(Wrappers.<GbDeviceRecord>lambdaQuery()
                .lt(GbDeviceRecord::getUpdateTime, LocalDateTime.now().minusDays(retainDays)));
    }

    public List<GbStatusLog> statusLogs(String deviceId, String channelId,
                                        String startTime, String endTime) {
        LambdaQueryWrapper<GbStatusLog> q = Queries.deviceAndChannel(
                Wrappers.<GbStatusLog>lambdaQuery(),
                GbStatusLog::getDeviceId, GbStatusLog::getChannelId, deviceId, channelId);
        Queries.timeRange(q, GbStatusLog::getCreateTime, startTime, endTime);
        return statusLogMapper.selectList(q.orderByDesc(GbStatusLog::getCreateTime));
    }

    /**
     * 记录一条移动位置（{@code MobilePosition} 主动上报，或报警附带坐标）。
     * 经纬度全为 0 的记录直接丢弃（设备未装 GPS），否则轨迹图上全是「在几内亚湾」的点。
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
        LambdaQueryWrapper<GbPositionLog> q = Queries.deviceAndChannel(
                Wrappers.<GbPositionLog>lambdaQuery(),
                GbPositionLog::getDeviceId, GbPositionLog::getChannelId, deviceId, channelId);
        Queries.timeRange(q, GbPositionLog::getCreateTime, startTime, endTime);
        return positionLogMapper.selectList(q.orderByDesc(GbPositionLog::getCreateTime));
    }

    private static BigDecimal decimalOf(Double v) {
        return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }

    private static Double firstDouble(ManscdpMessage msg, String tag) {
        return Numbers.doubleOrNull(msg.firstText(tag));
    }

    // ==================== 内部：MANSCDP 取值 ====================

    private static void applyText(ManscdpMessage msg, String tag, java.util.function.Consumer<String> setter) {
        String v = msg.firstText(tag);
        if (v != null && !v.isBlank()) {
            // 设备普遍以 GBK 字节发中文，解码器仅在出现替换字符时才回退 GBK，
            // 「GBK 字节恰为合法 UTF-8」时会静默产出乱码（如「平台」→「ƽ̨」）
            setter.accept(CharsetFix.repair(v));
        }
    }

    private static void applyDouble(ManscdpMessage msg, String tag,
                                    java.util.function.Consumer<Double> setter) {
        Double v = Numbers.doubleOrNull(msg.firstText(tag));
        if (v != null) {
            setter.accept(v);
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
