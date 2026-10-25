package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeRegistrar;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbCascade;
import io.github.rurulalanana.starter.entity.GbCascadeChannel;
import io.github.rurulalanana.starter.entity.GbChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 级联上行链路管理：让「本级作为下级平台向上级注册」真正跑起来。
 *
 * <h3>为什么需要这一层</h3>
 * 改造前级联页能存配置、能点「推送目录」，但<b>一条信令都发不出去</b>：
 * <ul>
 *   <li>没有主动 REGISTER，上级永远不知道本级存在；</li>
 *   <li>推送目录调 {@code client.sendMessage(parentId, ...)}，而该方法要先在会话表
 *       里查上级地址 —— 上级从不注册到本级，必然抛「设备未注册或会话已失效」。</li>
 * </ul>
 * 也就是说「添加上级平台」只是把配置写进了库。本类补上真正的链路：
 * 注册 → 保活 → 过期前重注册 → 断线重试，并把结果同步到 {@code gb_cascade.online}。
 *
 * <h3>状态放在内存，配置放在库</h3>
 * {@link CascadeRegistrar} 持有 TCP/TLS 无关的 SIP 会话状态（是否已注册、
 * 失败原因、realm），这类状态天然属于进程；而「上级地址/密码/共享哪些通道」
 * 属于配置，落库。两者分开后，重启只需重新注册一次，配置不会丢。
 *
 * <h3>为什么不复用 CascadeManager</h3>
 * {@code CascadeManager} 管的是<b>下行</b>（别的平台注册到我们），
 * 用的是注册表寻址；上行方向上级地址只存在于配置里，两者寻址方式根本不同。
 * 混在一起会让「查不到会话」这个错误反复出现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CascadeLinkService {

    private final CascadeService cascadeService;
    private final DeviceService deviceService;
    private final Gb28181SipClientProvider clientProvider;
    private final Gb28181Properties props;
    /** 上级编码登记表：媒体层用它分辨「这个 INVITE 来自我的上级」。 */
    private final CascadeParentRegistry parentRegistry;
    /** 推送媒体流用（上行点播时把本地流推给上级）。 */
    private final StreamService streamService;

    /**
     * cascadeId → 上行链路。
     *
     * <p>只在启用状态存在：停用即注销并从表中移除，避免「配置已停用但仍在发心跳」。
     */
    private final Map<String, CascadeRegistrar> links = new ConcurrentHashMap<>();

    /** cascadeId → 上一次保活时间戳（毫秒）。 */
    private final Map<String, Long> lastKeepaliveAt = new ConcurrentHashMap<>();

    /** 级联功能是否可用（SIP 服务已启动）。 */
    public boolean available() {
        return clientProvider.client() != null && sipServer() != null;
    }

    /**
     * 刷新上级编码登记表。
     *
     * <p>必须在配置变更（新增/删除/启停）后调用，否则：
     * <ul>
     *   <li>新增上级后，它点播时会被当成普通设备 INVITE，永远拿不到流；</li>
     *   <li>删除上级后，它仍然能用旧身份让我们推流（多余的信息暴露）。</li>
     * </ul>
     * 只登记<b>已启用</b>的：停用的级联不应能点播本级通道。
     */
    public void refreshParentRegistry() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (GbCascade c : cascadeService.enabledCascades()) {
            if (c.getParentId() != null && !c.getParentId().trim().isEmpty()) {
                ids.add(c.getParentId().trim());
            }
        }
        parentRegistry.replace(ids);
    }

    // ==================== 启停 ====================

    /**
     * 启用一条级联（注册并纳入保活）。
     *
     * <p><b>注册失败也返回 {@code false} 而不抛异常</b>：上级不可达是常态
     * （对方没开机、网络还没通），此时应当把失败原因写进配置供界面展示，
     * 由定时任务持续重试 —— 抛异常会让「保存配置」这个动作看起来失败了。
     *
     * @return 是否注册成功
     */
    public boolean enable(String cascadeId) {
        GbCascade cascade = cascadeService.find(cascadeId);
        if (cascade == null) {
            return false;
        }
        CascadeRegistrar registrar = links.get(cascadeId);
        if (registrar == null) {
            registrar = build(cascade);
            if (registrar == null) {
                return false;
            }
            links.put(cascadeId, registrar);
        }
        boolean ok = registrar.register();
        cascadeService.updateOnlineState(cascadeId, ok, registrar.lastError());
        if (ok) {
            lastKeepaliveAt.put(cascadeId, System.currentTimeMillis());
        }
        return ok;
    }

    /**
     * 停用一条级联：先向上级注销，再移除链路。
     *
     * <p>必须发 Expires:0 注销。否则上级会一直认为本级在线直到注册有效期自然过期，
     * 期间它点播本级的通道会全部失败，而上级侧看不到任何原因 ——
     * 这种「对端视角的幽灵在线」是最难排查的一类问题。
     */
    public void disable(String cascadeId) {
        CascadeRegistrar registrar = links.remove(cascadeId);
        lastKeepaliveAt.remove(cascadeId);
        if (registrar != null) {
            registrar.unregister();
        }
        cascadeService.updateOnlineState(cascadeId, false, null);
    }

    /** 重启一条链路（配置变更后调用：地址/密码/共享通道可能都变了）。 */
    public void restart(String cascadeId) {
        disable(cascadeId);
        GbCascade cascade = cascadeService.find(cascadeId);
        if (cascade != null && Boolean.TRUE.equals(cascade.getEnabled())) {
            enable(cascadeId);
        }
    }

    /** 移除链路（删除配置时调用，不再查库）。 */
    public void forget(String cascadeId) {
        CascadeRegistrar registrar = links.remove(cascadeId);
        lastKeepaliveAt.remove(cascadeId);
        if (registrar != null) {
            registrar.unregister();
        }
    }

    /** 当前内存中的链路（诊断用）。 */
    public CascadeRegistrar link(String cascadeId) {
        return links.get(cascadeId);
    }

    // ==================== 定时驱动 ====================

    /**
     * 链路维护（由 {@code PlatformScheduler} 每次 tick 调用）。
     *
     * <p>三件事，按优先级：
     * <ol>
     *   <li><b>未注册</b> → 尝试注册（含指数退避由 tick 间隔自然提供）；</li>
     *   <li><b>已注册且接近过期</b> → 重新注册（有效期通常 3600s，
     *       提前 1/4 周期重注册；等到过期再注册会有一段上级认为我们离线的窗口）；</li>
     *   <li><b>已注册且到了保活点</b> → 发心跳。</li>
     * </ol>
     *
     * <p><b>为什么每次只处理一条</b>：注册是同步阻塞（最长 5 秒等响应）。
     * 一个 tick 里串行处理 10 条上级如果都不可达，会把调度线程占住 50 秒。
     * 分摊到多个 tick 既不影响实时性（一个 tick 5 秒），也不会卡住其它任务。
     */
    public void tick() {
        if (!available()) {
            return;
        }
        List<GbCascade> enabled = cascadeService.enabledCascades();
        // 第一步先把登记表对齐：应用刚启动时它是空的，
        // 而上级可能在注册完成前就来点播
        refreshParentRegistry();
        if (enabled.isEmpty()) {
            // 没有启用的级联：清掉可能残留的链路（例如在别处直接改了库）
            for (String id : links.keySet()) {
                disable(id);
            }
            return;
        }
        long now = System.currentTimeMillis();
        for (GbCascade cascade : enabled) {
            String id = cascade.getCascadeId();
            CascadeRegistrar registrar = links.get(id);
            if (registrar == null) {
                // 新启用或应用刚启动：建链路并注册
                if (enable(id)) {
                    return;
                }
                continue;
            }
            if (!registrar.isRegistered()) {
                // 断线重试。失败原因每次刷新，界面能看到「上级不通」而不是一直「未知」
                boolean ok = registrar.register();
                cascadeService.updateOnlineState(id, ok, registrar.lastError());
                if (ok) {
                    lastKeepaliveAt.put(id, now);
                }
                return;
            }
            // 接近过期：提前重注册。留 1/4 余量，给一次重试机会
            long expiresMs = registrar.registerExpires() * 1000L;
            long sinceSuccess = now - registrar.lastSuccessAt();
            if (sinceSuccess >= expiresMs * 3 / 4) {
                boolean ok = registrar.register();
                cascadeService.updateOnlineState(id, ok, registrar.lastError());
                if (ok) {
                    lastKeepaliveAt.put(id, now);
                }
                return;
            }
            // 保活
            Integer interval = cascade.getKeepaliveInterval();
            long keepaliveMs = (interval == null || interval <= 0 ? 60 : interval) * 1000L;
            Long last = lastKeepaliveAt.get(id);
            if (last == null || now - last >= keepaliveMs) {
                if (registrar.keepalive()) {
                    lastKeepaliveAt.put(id, now);
                }
                return;
            }
        }
    }

    /**
     * 执行一次级联上行推流。
     *
     * <p>由 {@code SipEventBridge#onUplinkCascadePlay} 在异步线程里调用。
     * 三步：
     * <ol>
     *   <li>通道编码 → 找到所属设备（推流必须先让设备把流送到本级）；</li>
     *   <li>启动本地直播流（已有则复用，不会重复邀请设备）；</li>
     *   <li>找到 ZLM 里已存在的媒体源，把它的 RTP 直接推给上级。</li>
     * </ol>
     *
     * <p><b>第三步是关键</b>：不能新建一个 {@code MK_MEDIA} 再推 —— 那是
     * 需要喂帧的输入侧，没人喂就发不出任何数据，上级会报「none rtp data」。
     * 必须 {@code mk_media_source_find2} 找到真实存在的流再转推。
     *
     * @return 是否成功发起推流
     */
    public boolean pushToUplink(String channelId, String dstIp, int dstPort, String ssrc) {
        return pushToUplink(null, channelId, dstIp, dstPort, ssrc, false);
    }

    /**
     * 同上，但带上上级编码与 SDP 声明的传输方式。
     *
     * @param platformId 上级平台编码；为空则不做配置查询
     * @param sdpTcp     上级 INVITE 的 SDP 是否要求 TCP
     */
    public boolean pushToUplink(String platformId, String channelId, String dstIp,
                                int dstPort, String ssrc, boolean sdpTcp) {
        boolean tcp = decideTcp(platformId, sdpTcp);
        GbChannel channel = deviceService.findChannel(channelId);
        if (channel == null) {
            log.warn("级联上行推流失败：通道不存在 {}", channelId);
            return false;
        }
        String deviceId = channel.getDeviceId();
        if (deviceId == null || deviceId.trim().isEmpty()) {
            log.warn("级联上行推流失败：通道 {} 没有所属设备", channelId);
            return false;
        }
        try {
            // 启动（或复用）本地直播流。这一步会邀请设备推 PS 到本级。
            streamService.startLive(deviceId, channelId);
        } catch (Exception e) {
            // 流已经在跑时某些实现会抛「已存在」，不该阻断后续推流尝试
            log.warn("启动本地流失败（继续尝试推流）: device={} channel={} err={}",
                    deviceId, channelId, e.getMessage());
        }
        var media = clientProvider.platform().mediaServer();
        if (media == null) {
            log.warn("级联上行推流失败：媒体服务未就绪");
            return false;
        }
        // 单端口模式下 ZLM 固定 app=rtp、stream=<设备>_<通道>
        boolean ok = media.startSendRtpToSource("rtp", deviceId + "_" + channelId,
                dstIp, dstPort, ssrc, 20_000L, tcp);
        if (ok) {
            log.info("级联上行推流成功: {} → {}:{} ({})", channelId, dstIp, dstPort,
                    tcp ? "TCP主动" : "UDP主动");
        } else {
            log.warn("级联上行推流失败：媒体源未就绪 {} → {}:{}",
                    channelId, dstIp, dstPort);
        }
        return ok;
    }

    /**
     * 决定本次推流用 TCP 还是 UDP。
     *
     * <p>优先级：<b>级联配置 {@code mediaTransport}</b> &gt; 上级 SDP 的声明。
     *
     * <p>为什么配置能覆盖 SDP：实测遇到上级 INVITE 里写 {@code RTP/AVP}（UDP），
     * 但它那侧 UDP 入站端口没放通 —— 包全部发出去了、RTP/PS 格式完全正确，
     * 对方 {@code InBytes} 恒为 0，只能报「none rtp data receive」。
     * 同一上级加 {@code transport=tcp} 点播后 TCP 一次就通。
     * 这种「上级自己声明错了」的场景，只能让我们这侧强制覆盖。
     */
    private boolean decideTcp(String platformId, boolean sdpTcp) {
        if (platformId == null || platformId.trim().isEmpty()) {
            return sdpTcp;
        }
        try {
            GbCascade cascade = cascadeService.findByParentId(platformId.trim());
            if (cascade == null || cascade.getMediaTransport() == null) {
                return sdpTcp;
            }
            String mode = cascade.getMediaTransport().trim().toLowerCase();
            if ("tcp".equals(mode)) {
                if (!sdpTcp) {
                    log.info("级联推流强制 TCP（覆盖上级 SDP 的 UDP 声明）: 上级={}", platformId);
                }
                return true;
            }
            if ("udp".equals(mode)) {
                if (sdpTcp) {
                    log.info("级联推流强制 UDP（覆盖上级 SDP 的 TCP 声明）: 上级={}", platformId);
                }
                return false;
            }
        } catch (Exception e) {
            log.debug("查询级联传输配置失败，回退到 SDP 声明: {}", e.getMessage());
        }
        return sdpTcp;
    }

    /** 停止某通道的级联上行推流（上级 BYE 时调用）。 */
    public void stopUplinkPush(String channelId) {
        GbChannel channel = deviceService.findChannel(channelId);
        if (channel == null || channel.getDeviceId() == null) {
            return;
        }
        var media = clientProvider.platform().mediaServer();
        if (media != null) {
            media.stopSendRtpToSource("rtp", channel.getDeviceId() + "_" + channelId);
        }
    }

    // ==================== 目录 ====================

    /**
     * 把某条级联的共享通道目录推送给上级。
     *
     * @return 推送的通道数；链路不可用时抛异常说明原因
     */
    public int pushCatalog(String cascadeId) {
        GbCascade cascade = cascadeService.find(cascadeId);
        if (cascade == null) {
            throw io.github.rurulalanana.starter.common.BizException.notFound(
                    "级联配置不存在：" + cascadeId);
        }
        CascadeRegistrar registrar = links.get(cascadeId);
        if (registrar == null) {
            /*
             * 没建链路时不要静默失败，也不要用旧的 sendMessage 路径。
             * 明确告诉调用方「先注册」—— 上级根本没认出我们，
             * 这时候推目录过去它也无处安放（不知道属于哪个平台）。
             */
            throw io.github.rurulalanana.starter.common.BizException.badRequest(
                    "该级联尚未注册到上级，请先启用并确认在线后再推送目录");
        }
        List<CatalogItem> items = sharedCatalogItems(cascade);
        if (items.isEmpty()) {
            log.info("级联 {} 无共享通道，跳过目录推送", cascadeId);
            return 0;
        }
        int groupSize = cascade.getCatalogGroupSize() == null || cascade.getCatalogGroupSize() <= 0
                ? 100 : cascade.getCatalogGroupSize();
        int pushed = 0;
        for (int i = 0; i < items.size(); i += groupSize) {
            List<CatalogItem> group = items.subList(i, Math.min(i + groupSize, items.size()));
            if (!registrar.pushCatalog(group, 0)) {
                throw io.github.rurulalanana.starter.common.BizException.badRequest(
                        "目录推送失败：" + registrar.lastError());
            }
            pushed += group.size();
        }
        log.info("级联目录已推送: cascade={} 上级={} 通道数={}",
                cascadeId, cascade.getParentId(), pushed);
        return pushed;
    }

    /** 推送单条通道的目录变更。 */
    public void pushChannelCatalog(String cascadeId, String channelId, String event) {
        CascadeRegistrar registrar = links.get(cascadeId);
        if (registrar == null) {
            throw io.github.rurulalanana.starter.common.BizException.badRequest(
                    "该级联尚未注册到上级，无法推送通道变更");
        }
        GbChannel channel = deviceService.findChannel(channelId);
        if (channel == null) {
            throw io.github.rurulalanana.starter.common.BizException.notFound(
                    "通道不存在：" + channelId);
        }
        String useEvent = event == null || event.isBlank() ? "UPDATE" : event.trim().toUpperCase();
        java.util.List<CatalogItem> one = java.util.Collections.singletonList(
                cascadeService.toCatalogItem(channel, useEvent));
        if (!registrar.pushCatalog(one, 0)) {
            throw io.github.rurulalanana.starter.common.BizException.badRequest(
                    "通道目录推送失败：" + registrar.lastError());
        }
    }

    /**
     * 回复上级的目录查询。
     *
     * <p>由 {@code SipEventBridge} 在收到 {@code Catalog/Query} 且来源是已配置的
     * 上级平台时调用。
     *
     * @param cascadeId 命中的级联配置
     * @param querySn   上级原始 SN（<b>必须原样带回</b>，否则上级按超时丢弃）
     * @param targetId  上级查询里的目标编码
     * @return 回复的通道数
     */
    public int replyCatalogQuery(String cascadeId, int querySn, String targetId) {
        GbCascade cascade = cascadeService.find(cascadeId);
        if (cascade == null) {
            return 0;
        }
        CascadeRegistrar registrar = links.get(cascadeId);
        if (registrar == null) {
            // 上级查过来但链路还没建：先建一条（说明上级已经把请求发到我们这里，
            // 网络是通的，这时注册大概率能成功）
            registrar = build(cascade);
            if (registrar == null) {
                return 0;
            }
            links.put(cascadeId, registrar);
        }
        List<CatalogItem> items = sharedCatalogItems(cascade);
        registrar.replyCatalog(querySn, targetId, items);
        log.info("已应答上级目录查询: cascade={} sn={} 通道数={}",
                cascadeId, querySn, items.size());
        return items.size();
    }

    // ==================== 控制（云台 / 预置位 / 拉框缩放 …） ====================

    /**
     * 转发上级下发的设备控制指令给对应设备。
     *
     * <p>这是级联里<b>唯一需要「回程」</b>的能力：点播是上级直接连我们的媒体端口，
     * 目录是我们主动推，只有控制指令要走
     * 「上级 → 本级 → 设备 → （应答）→ 本级 → 上级」。
     *
     * <p><b>为什么要原样转发 XML 而不是逐字段重建</b>：见
     * {@code ManscdpBuilder#relinkControl} 的注释 —— 控制命令族十几种、
     * {@code PTZCmd} 是不透明 8 字节串，重建必然丢语义。
     *
     * @param cascadeId 命中的级联配置
     * @param channelId 上级请求里的通道编码（就是我们的通道编码）
     * @param control   上级的原始 {@code <Control>} 消息
     * @return 是否成功转发给设备
     */
    public boolean forwardControl(String cascadeId, String channelId, ManscdpMessage control) {
        GbCascade cascade = cascadeService.find(cascadeId);
        if (cascade == null) {
            return false;
        }
        /*
         * 授权校验，两道：
         *   1) allowControl —— 该级联是否允许上级控制（配置开关）
         *   2) 通道必须在共享集合里 —— 否则上级能控制它「看不见」的通道。
         *      目录只推了共享通道，正常上级不会知道其它编码；但不校验就等于
         *      信任对端不会构造任意编码，公网环境下不该有这种信任。
         */
        if (!Boolean.TRUE.equals(cascade.getAllowControl())) {
            log.warn("级联 {} 未开启「允许控制」，拒绝上级控制通道 {}", cascadeId, channelId);
            return false;
        }
        if (!isShared(cascade, channelId)) {
            log.warn("通道 {} 未共享给级联 {}，拒绝其控制指令（避免越权操作）",
                    channelId, cascadeId);
            return false;
        }
        String deviceId = deviceService.deviceIdOfChannel(channelId);
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("控制指令转发失败：通道 {} 找不到所属设备（可能尚未同步目录）", channelId);
            return false;
        }
        Gb28181SipClient client = clientProvider.client();
        if (client == null) {
            log.warn("控制指令转发失败：SIP 信令服务未就绪");
            return false;
        }
        try {
            /*
             * 必须用本级的新 SN，且 SIP 层用「设备编码」寻址（DeviceID 仍是通道编码）。
             * 这两点与 ControlService 的直连控制完全一致，代价是不能复用它的
             * 方法 —— 那里的入口参数是「通道编码 + 命令码」，而这里手上是
             * 一条完整的、已被上级编码好的 Control 报文。
             */
            String xml = io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder
                    .relinkControl(client.nextSn(), control);
            client.sendMessage(deviceId, xml);
            log.info("级联控制已转发: 上级={} 通道={} 设备={} 指令={}",
                    cascade.getParentId(), channelId, deviceId, describeControl(control));
            return true;
        } catch (Exception e) {
            log.warn("级联控制转发失败: 通道={} 设备={} err={}",
                    channelId, deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * 应答上级的控制指令。
     *
     * <p>语义是「<b>已受理</b>」：设备对云台指令通常不做应用层应答，
     * 若等设备回 {@code <Response>} 再回上级，上级必然超时并报控制失败，
     * 而云台其实已经动了。
     */
    public void replyControl(String cascadeId, int sn, String channelId, boolean ok) {
        CascadeRegistrar registrar = links.get(cascadeId);
        if (registrar == null) {
            /*
             * 链路还没建（定时器每轮只处理一条链路，刚启动时可能还没轮到）。
             * 上级既然能把指令发过来，说明网络通，这里补建一条再应答 ——
             * 否则上级等不到 <Response>，用户看到的是「控制失败」。
             */
            GbCascade cascade = cascadeService.find(cascadeId);
            if (cascade == null) {
                return;
            }
            registrar = build(cascade);
            if (registrar == null) {
                return;
            }
            links.put(cascadeId, registrar);
        }
        registrar.replyDeviceControl(sn, channelId, ok);
    }

    /** 通道是否在级联的共享集合里（含「共享全部通道」开关）。 */
    private boolean isShared(GbCascade cascade, String channelId) {
        if (Boolean.TRUE.equals(cascade.getShareAllChannel())) {
            return true;
        }
        // 控制指令只带了通道编码，没带设备编码（上级的 DeviceID 就是通道编码），
        // 所以这里按通道编码匹配；deviceId 传 null 会退化成只比通道编码。
        return cascadeService.isChannelShared(cascade.getCascadeId(), null, channelId);
    }

    /** 控制指令的可读描述，仅用于日志排查（不参与业务）。 */
    private static String describeControl(ManscdpMessage msg) {
        for (String name : msg.childNames()) {
            if ("SN".equals(name) || "CmdType".equals(name) || "DeviceID".equals(name)) {
                continue;
            }
            String v = msg.firstText(name);
            if (v != null && !v.isBlank() && v.length() <= 64) {
                return name + "=" + v;
            }
        }
        return msg.getCmdType();
    }

    /** 某条级联要共享给上级的通道（转成 CatalogItem）。 */
    private List<CatalogItem> sharedCatalogItems(GbCascade cascade) {        List<GbCascadeChannel> shared = cascadeService.sharedChannels(cascade.getCascadeId());
        List<CatalogItem> items = new java.util.ArrayList<>();
        boolean all = Boolean.TRUE.equals(cascade.getShareAllChannel());
        Collection<GbChannel> channels = all
                ? deviceService.allChannels()
                : channelsOf(shared);
        for (GbChannel c : channels) {
            items.add(cascadeService.toCatalogItem(c, "ON"));
        }
        return items;
    }

    private Collection<GbChannel> channelsOf(List<GbCascadeChannel> shared) {
        List<GbChannel> out = new java.util.ArrayList<>();
        for (GbCascadeChannel sc : shared) {
            // 表里会保留「曾经共享、后来取消」的记录（shared=false），
            // 漏掉这个判断就会把用户已经关掉的通道继续推给上级 —— 越权暴露。
            if (!Boolean.TRUE.equals(sc.getShared())) {
                continue;
            }
            // 必须按「设备 + 通道」定位：通道编码只在单设备内唯一，
            // 两参数版本才有这个精度（deviceId 为 null 时它会自动退化为单参数查）。
            GbChannel c = deviceService.findChannel(sc.getDeviceId(), sc.getChannelId());
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    // ==================== 内部 ====================

    /** 按配置构造一条上行链路；必要信息缺失时返回 {@code null} 并告警。 */
    private CascadeRegistrar build(GbCascade cascade) {
        Gb28181SipServer sip = sipServer();
        if (sip == null) {
            log.warn("SIP 服务未启动，无法建立级联链路");
            return null;
        }
        if (cascade.getParentIp() == null || cascade.getParentIp().isBlank()
                || cascade.getParentPort() == null || cascade.getParentPort() <= 0) {
            cascadeService.updateOnlineState(cascade.getCascadeId(), false,
                    "未配置上级平台 IP 或端口");
            return null;
        }
        // 本级对外地址：优先用级联配置里的 localIp（多网卡时必须显式指定），
        // 否则回落到设备可达的通告地址 —— 绝不能是 0.0.0.0，
        // 上级按 Contact 回连，通配地址会让它发不回来
        String localIp = cascade.getLocalIp();
        if (localIp == null || localIp.isBlank()) {
            localIp = props.getSipAdvertisedIp();
        }
        if (localIp == null || localIp.isBlank()) {
            localIp = props.getMediaIp();
        }
        int localPort = cascade.getLocalPort() == null || cascade.getLocalPort() <= 0
                ? props.getSipPort() : cascade.getLocalPort();
        String localId = cascade.getLocalId() == null || cascade.getLocalId().isBlank()
                ? props.getPlatformId() : cascade.getLocalId();
        String password = cascade.getPassword() != null && !cascade.getPassword().isBlank()
                ? cascade.getPassword() : cascade.getParentPassword();

        return new CascadeRegistrar(sip,
                cascade.getParentId(),
                cascade.getParentDomain(),
                cascade.getParentIp(),
                cascade.getParentPort(),
                localId,
                localIp,
                localPort,
                cascade.getUsername(),
                password,
                cascade.getExpire() == null || cascade.getExpire() <= 0
                        ? 3600 : cascade.getExpire(),
                // 字符集必须传给链路：上级按它自己记录的「设备字符集」解码，
                // 发错了通道名就是乱码（ID/状态却正常，极易被忽略）
                cascade.getCharset());
    }

    private Gb28181SipServer sipServer() {
        return clientProvider.platform().sipServer();
    }
}
