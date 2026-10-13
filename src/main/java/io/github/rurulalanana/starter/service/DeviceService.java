package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.entity.GbPreset;
import io.github.rurulalanana.starter.entity.GbStatusLog;
import io.github.rurulalanana.starter.mapper.GbChannelMapper;
import io.github.rurulalanana.starter.mapper.GbDeviceMapper;
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

    /** 按通道编号查询。 */
    public GbChannel findChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        return channelMapper.selectOne(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getChannelId, channelId).last("limit 1"));
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
        for (GbDevice d : silent) {
            d.setOnline(false);
            d.setStatus("OFF");
            d.setUpdateTime(LocalDateTime.now());
            deviceMapper.updateById(d);
            touchDeviceChannels(d.getDeviceId(), "OFF");
            logStatus(d.getDeviceId(), null, "ON", "OFF", "心跳超时", "silent");
        }
        if (!silent.isEmpty()) {
            log.info("心跳超时下线 {} 个设备（阈值 {}s）", silent.size(), timeoutSeconds);
        }
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
     * @param parentDeviceId 应答来源设备（目录项归属该设备）
     * @param items          目录项
     */
    @Transactional
    public void saveCatalog(String parentDeviceId, Collection<CatalogItem> items) {
        if (items == null || items.isEmpty()) {
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
        int channelCount = 0;
        for (CatalogItem item : items) {
            if (item == null || item.getDeviceId() == null || item.getDeviceId().isBlank()) {
                continue;
            }
            if (item.getDeviceId().equals(parentDeviceId)) {
                applyDeviceInfo(device, item);
                deviceMapper.updateById(device);
                continue;
            }
            saveChannel(device, item);
            channelCount++;
        }
        if (channelCount > 0) {
            device.setChannelCount(channelCount);
        } else {
            // 目录为空：设备本身就是一路摄像头（直连相机常见），
            // 为它建一条「自身通道」，否则前端 channellist 永远是空的。
            materializeSelfChannel(device);
        }
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        log.info("目录已同步: device={} 通道数={}", parentDeviceId, device.getChannelCount());
    }

    /**
     * 把设备自身登记为一条通道（供定时任务调用）。
     *
     * <p>很多 IPC/NVR 的 Catalog 应答不含子通道（或 Channel=1 但不下发 Item），
     * 此时设备编号本身即可作为通道编号点播。不建这条记录的话，
     * 前端通道列表为空，用户看不到任何可播放的通道。
     *
     * @return true 表示本次新建了通道
     */
    @Transactional
    public boolean ensureSelfChannel(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        GbDevice device = findDevice(deviceId);
        if (device == null || !Boolean.TRUE.equals(device.getOnline())) {
            return false;
        }
        // 已有任何通道（含子通道）就不再补自身通道
        Long existing = channelMapper.selectCount(Wrappers.<GbChannel>lambdaQuery()
                .eq(GbChannel::getDeviceId, deviceId));
        if (existing != null && existing > 0) {
            return false;
        }
        materializeSelfChannel(device);
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        return true;
    }

    /**
     * 把设备自身登记为一条通道。
     *
     * <p>很多 IPC/NVR 的 Catalog 应答不含子通道（或 Channel=1 但不下发 Item），
     * 此时设备编号本身即可作为通道编号点播。不建这条记录的话，
     * 前端通道列表为空，用户看不到任何可播放的通道。
     */
    private void materializeSelfChannel(GbDevice device) {
        String deviceId = device.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        GbChannel existing = findChannel(deviceId);
        if (existing != null) {
            // 已存在则只刷新在线状态与名称，不覆盖用户改过的自定义字段
            if (existing.getStatus() == null || !existing.getStatus().equals(device.getStatus())) {
                existing.setStatus(device.getStatus());
                existing.setUpdateTime(LocalDateTime.now());
                channelMapper.updateById(existing);
            }
            if (device.getChannelCount() == null || device.getChannelCount() == 0) {
                device.setChannelCount(1);
            }
            return;
        }
        GbChannel channel = new GbChannel();
        channel.setChannelId(deviceId);
        channel.setDeviceId(deviceId);
        channel.setName(device.displayName());
        channel.setManufacturer(device.getManufacturer());
        channel.setModel(device.getModel());
        channel.setCivilCode(device.getCivilCode());
        channel.setAddress(device.getAddress());
        channel.setParentId(device.getParentId());
        channel.setLongitude(device.getLongitude());
        channel.setLatitude(device.getLatitude());
        channel.setPtzType(device.getPtzType());
        channel.setStatus(device.getStatus());
        channel.setRegisterWay(1);
        channel.setOnDemand(true);
        channel.setCreateTime(LocalDateTime.now());
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.insert(channel);
        device.setChannelCount(1);
        log.info("目录无子通道，已把设备自身登记为通道: device={}", deviceId);
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
        applyInt(msg, "Channel", device::setChannelCount);
        applyDouble(msg, "Longitude", v -> device.setLongitude(BigDecimal.valueOf(v)));
        applyDouble(msg, "Latitude", v -> device.setLatitude(BigDecimal.valueOf(v)));
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    private void saveChannel(GbDevice device, CatalogItem item) {
        GbChannel channel = findChannel(item.getDeviceId());
        if (channel == null) {
            channel = new GbChannel();
            channel.setChannelId(item.getDeviceId());
            channel.setCreateTime(LocalDateTime.now());
        }
        channel.setDeviceId(device.getDeviceId());
        channel.setName(item.getName());
        channel.setManufacturer(item.getManufacturer());
        channel.setModel(item.getModel());
        channel.setOwner(null);
        channel.setCivilCode(item.getCivilCode());
        channel.setBlock(item.getBlock());
        channel.setAddress(item.getAddress());
        channel.setParental(item.getParental());
        channel.setParentId(item.getParentId());
        channel.setRegisterWay(item.getRegisterWay());
        channel.setSecrecy(item.getSecrecy());
        channel.setIpAddress(item.getIpAddress());
        channel.setPort(item.getPort());
        channel.setPtzType(item.getPtzType());
        channel.setLongitude(item.getLongitude() == null ? null : BigDecimal.valueOf(item.getLongitude()));
        channel.setLatitude(item.getLatitude() == null ? null : BigDecimal.valueOf(item.getLatitude()));
        channel.setResolution(item.getResolution());
        channel.setDownloadSpeed(item.getDownloadSpeed());
        channel.setBusinessGroupId(item.getBusinessGroupId());
        channel.setStatus(item.getStatus());
        channel.setUpdateTime(LocalDateTime.now());
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);
        }
    }

    private void applyDeviceInfo(GbDevice device, CatalogItem item) {
        if (item.getName() != null) {
            device.setName(item.getName());
        }
        if (item.getManufacturer() != null) {
            device.setManufacturer(item.getManufacturer());
        }
        if (item.getModel() != null) {
            device.setModel(item.getModel());
        }
        if (item.getCivilCode() != null) {
            device.setCivilCode(item.getCivilCode());
        }
        if (item.getAddress() != null) {
            device.setAddress(item.getAddress());
        }
        if (item.getPtzType() != null) {
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

    /** 记录 / 清除通道的当前直播流。 */
    @Transactional
    public void setChannelStream(String channelId, String streamId) {
        GbChannel channel = findChannel(channelId);
        if (channel == null) {
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
        List<GbChannel> channels = channelsOf(deviceId);
        for (GbChannel c : channels) {
            if (status.equals(c.getStatus())) {
                continue;
            }
            c.setStatus(status);
            c.setUpdateTime(LocalDateTime.now());
            channelMapper.updateById(c);
        }
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

    // ==================== 内部：MANSCDP 取值 ====================

    private static void applyText(ManscdpMessage msg, String tag, java.util.function.Consumer<String> setter) {
        String v = msg.firstText(tag);
        if (v != null && !v.isBlank()) {
            setter.accept(v);
        }
    }

    private static void applyInt(ManscdpMessage msg, String tag, java.util.function.Consumer<Integer> setter) {
        Integer v = msg.intOf(tag);
        if (v != null) {
            setter.accept(v);
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
