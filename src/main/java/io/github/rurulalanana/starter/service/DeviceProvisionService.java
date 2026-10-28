package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;

import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.mapper.GbDeviceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 非国标设备（SDK 直连）的纳管与目录同步。
 *
 * <h3>为什么单独一个服务，而不是塞进 DeviceService</h3>
 * {@code DeviceService} 的入口是 <b>SIP 事件</b>（注册/心跳/目录应答），
 * 数据是设备自己推上来的；而 SDK 直连是<b>平台主动去问</b>，
 * 触发方式、错误语义、重试策略都不同。混在一起会让
 * 「设备主动注册」和「平台主动探测」两条路径互相污染。
 *
 * <h3>复用点：一切落库都走既有实现</h3>
 * 本服务<b>不直接写通道表</b>：目录同步一律转成 {@link CatalogItem}，
 * 交给 {@link DeviceService#saveCatalog} —— 那里面已经处理好了
 * 「新增/更新/幽灵通道清理/设备自身通道」的全部边界情况。
 * 这样大华设备和国标设备在库里长得完全一样，
 * 前端的通道列表、点播、云台、录像接口一行都不用改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProvisionService {

    private final GbDeviceMapper deviceMapper;
    private final DeviceService deviceService;
    private final DeviceAccessRegistry accessRegistry;

    /**
     * 纳管一台 SDK 直连设备。
     *
     * <p>只落设备行，<b>不立即拉目录</b> —— 调用方通常在纳管后马上要返回、
     * 而拉目录可能耗时数秒（设备慢时十几秒）。目录由 {@link #refreshCatalog}
     * 单独触发，失败也不会把「设备已添加」这件事回滚掉。
     *
     * @param deviceId 平台内编号。允许沿用国标 20 位编码（推荐），
     *                 也可以直接用设备序列号 —— 它只是 {@code gb_device} 的主键语义，
     *                 不参与 SIP 寻址，因此不要求符合 GB 编码规则
     * @param host     设备地址
     * @param port     SDK 端口（大华默认 37777）
     * @param username 登录用户名
     * @param password 登录密码
     * @param name     显示名，可为空（默认用 deviceId）
     */
    @Transactional
    public GbDevice addDahua(String deviceId, String host, int port,
                             String username, String password, String name) {
        String id = trimToNull(deviceId);
        if (id == null) {
            throw BizException.badRequest("缺少设备编号 deviceId");
        }
        String ip = trimToNull(host);
        if (ip == null) {
            throw BizException.badRequest("缺少设备地址 host");
        }
        if (port <= 0 || port > 65535) {
            throw BizException.badRequest("设备端口不合法：" + port);
        }
        if (trimToNull(username) == null || trimToNull(password) == null) {
            throw BizException.badRequest("SDK 直连需要用户名与密码");
        }
        GbDevice existing = deviceService.findDevice(id);
        if (existing != null && DeviceKind.of(existing.getSource()) == DeviceKind.GB28181) {
            // 不允许把国标设备无声改成 SDK 直连：它的通道、会话、级联关系
            // 都建立在 SIP 之上，改了会让那些数据全部悬空。
            throw BizException.badRequest("设备 " + id + " 已通过国标接入，"
                    + "不能改为 SDK 直连；请换一个设备编号，或先删除原设备");
        }
        // 提前校验实现是否可用，避免写了一条永远连不上的设备记录
        accessRegistry.require(DeviceKind.DAHUA);

        GbDevice d = existing == null ? new GbDevice() : existing;
        d.setDeviceId(id);
        d.setName(name == null || name.isBlank() ? id : name.trim());
        d.setSource(DeviceKind.DAHUA.code());
        d.setRemoteIp(ip);
        d.setRemotePort(port);
        d.setUsername(username.trim());
        d.setPassword(password);
        d.setTransport("UDP");
        d.setUpdateTime(LocalDateTime.now());
        if (existing == null) {
            d.setStatus("OFF");
            d.setOnline(false);
            d.setCreateTime(LocalDateTime.now());
            deviceMapper.insert(d);
        } else {
            deviceMapper.updateById(d);
        }
        log.info("已纳管 SDK 直连设备: {}@{}:{} (source={})", id, ip, port, d.getSource());
        return d;
    }

    /**
     * 探测设备可达性并同步目录。
     *
     * <p>目录同步复用 {@link DeviceService#saveCatalog}：把 provider 报的通道
     * 转成 {@link CatalogItem} 后交给它做增删改，边界情况（幽灵通道、
     * 设备自身通道）都已在那里处理过。
     *
     * @return 本次同步到的通道数
     */
    @Transactional
    public int refreshCatalog(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DeviceKind kind = DeviceKind.of(device.getSource());
        if (kind == DeviceKind.GB28181) {
            // 国标设备的目录由设备应答驱动，这里不能主动「拉」
            throw BizException.badRequest("国标设备的目录请用 /device/fetchcatalog 下发查询");
        }
        DeviceAccessProvider provider = accessRegistry.require(kind);
        DeviceContext ctx = contextOf(device);
        if (!ctx.connectable()) {
            throw BizException.badRequest("设备连接信息不完整（host/port）：" + deviceId);
        }

        boolean online = provider.probe(ctx);
        device.setOnline(online);
        device.setStatus(online ? "ON" : "OFF");
        device.setUpdateTime(LocalDateTime.now());
        if (online) {
            device.setKeepaliveTime(LocalDateTime.now());
        }
        deviceMapper.updateById(device);
        if (!online) {
            log.warn("SDK 设备探测失败（不可达或凭据无效）: {}", ctx);
            return 0;
        }

        List<DeviceAccessProvider.ChannelInfo> channels = provider.listChannels(ctx);
        List<CatalogItem> items = toCatalogItems(channels);
        // fullSnapshot=true：provider 返回的是设备当前完整目录，
        // 因此设备上已删掉的通道也要跟着清掉
        deviceService.saveCatalog(deviceId, items, true, items.size(), 1);
        log.info("SDK 设备目录已同步: {} 通道数={}", deviceId, items.size());
        return items.size();
    }

    /**
     * provider 的通道信息 → 国标目录项。
     *
     * <p>只填 {@code saveCatalog} 实际会消费的字段：编号、名称、在离线。
     * 其余（经纬度、厂商…）留空 —— 大华 SDK 侧拿这些要额外调用，
     * 而它们在平台侧只用于展示，缺了不影响功能，后续需要再补。
     */
    private static List<CatalogItem> toCatalogItems(
            List<DeviceAccessProvider.ChannelInfo> channels) {
        List<CatalogItem> items = new ArrayList<>();
        if (channels == null) {
            return items;
        }
        for (DeviceAccessProvider.ChannelInfo c : channels) {
            if (c == null || c.channelCode() == null || c.channelCode().isBlank()) {
                continue;
            }
            CatalogItem item = CatalogItem.builder()
                    .deviceId(c.channelCode())
                    .name(c.name())
                    .status(c.online() ? "ON" : "OFF")
                    .build();
            items.add(item);
        }
        return items;
    }

    /** 设备记录 → 接入上下文。 */
    private static DeviceContext contextOf(GbDevice d) {
        return new DeviceContext(
                d.getDeviceId(),
                d.getRemoteIp(),
                d.getRemotePort() == null ? 0 : d.getRemotePort(),
                d.getUsername(),
                d.getPassword(),
                d.getChannelCount() == null ? 0 : d.getChannelCount());
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 删除设备时一并清理（复用既有实现，避免两套删除逻辑走偏）。 */
    public long countDahua() {
        return deviceMapper.selectCount(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getSource, DeviceKind.DAHUA.code()));
    }
}
