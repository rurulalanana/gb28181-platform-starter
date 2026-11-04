package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.Recording;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSessionManager;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.config.Gb28181Properties;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.mapper.GbDeviceRecordMapper;
import io.github.rurulalanana.starter.mapper.GbRecordMapper;
import io.github.rurulalanana.starter.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 录像服务：设备侧录像检索（标准 9.8）与云端录像管理。
 *
 * <p>设备录像文件在 NVR / SD 卡上，走 {@code RecordInfo} 查询 + 回放 INVITE；
 * 云端录像是 ZLM 把实时流录成的 MP4，元数据以 {@code gb_record} 表为准，
 * ZLM 内存索引只用于「正在录制中」的条目（重启后历史不丢）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    /**
     * 设备录像检索默认超时（秒）：必须大于设备自身内部等待。
     * 实测 LiveGBS 会向上级摄像机等 15 秒后回 {@code 400 wait for recordinfo timeout[15s]}，
     * 这里取 15 就永远在设备回话前放弃。
     */
    private static final int QUERY_TIMEOUT_SECONDS = 20;

    /** 等待聚合结果的轮询片长（毫秒），用于让设备的拒绝提前打断等待。 */
    private static final long WAIT_SLICE_MS = 200L;

    /** 等待流建立的总超时（毫秒）：设备推流通常 1~3 秒内开始。 */
    private static final long STREAM_READY_TIMEOUT_MS = 12_000;

    /** 等待流建立的轮询间隔（毫秒）。 */
    private static final long STREAM_READY_POLL_MS = 300;

    /** 归档时等待 ZLM 写终态的时长（毫秒）：录制停止是异步的，需给回调留时间。 */
    private static final long ARCHIVE_WAIT_MS = 3000;

    private static final long ARCHIVE_WAIT_POLL_MS = 100;

    private final Gb28181MediaPlatform platform;
    private final GbRecordMapper recordMapper;
    private final GbDeviceRecordMapper deviceRecordMapper;
    private final io.github.rurulalanana.starter.mapper.GbChannelMapper channelMapper;
    private final ObjectStorageService storage;
    private final io.github.rurulalanana.starter.storage.ObjectStorageProperties storageProps;
    private final StreamService streamService;
    private final Gb28181Properties props;
    /** SDK 直连设备的录像检索走同步调用，不经过 SIP。 */
    private final DahuaCapabilityService dahuaCapability;

    /** 设备录像检索的同通道串行锁：必须按通道而非全局，否则一次慢查询会卡住所有通道。 */
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    /** 相同查询窗口的短时缓存：{@code 通道|起|止} → 结果。 */
    private final Map<String, CachedRecords> recordCache = new ConcurrentHashMap<>();

    /** 同一查询窗口的在途请求：后来的直接等前一个的结果，不重复发 SIP。 */
    private final Map<String, CompletableFuture<DeviceRecordQuery>> recordInFlight =
            new ConcurrentHashMap<>();

    /** 检索结果缓存有效期（毫秒）：界面连续操作都在几秒内，再长会让刚录完的段落迟迟不出现。 */
    private static final long RECORD_CACHE_MS = 15_000L;

    /** 缓存条目上限：超过就整体清空（设备录像检索不是高频接口，够用即可）。 */
    private static final int RECORD_CACHE_MAX = 200;

    /** 一条检索结果缓存：必须带「设备是否应答」标志，否则命中缓存时无法传递。 */
    private record CachedRecords(DeviceRecordQuery result, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at >= RECORD_CACHE_MS;
        }
    }

    /** 清掉过期缓存；条目过多时整体清空（比维护 LRU 简单且足够）。 */
    private void trimRecordCache() {
        if (recordCache.size() > RECORD_CACHE_MAX) {
            recordCache.clear();
            return;
        }
        recordCache.entrySet().removeIf(e -> e.getValue().expired());
    }

    /**
     * 已删除录像的墓碑（文件路径 → 删除时刻）：删除接口先删文件再删记录，
     * 文件删除失败时下一次对账会把刚删的录像重新登记回来。内存实现，重启后丢失属可接受降级。
     */
    private final Map<String, Long> deletedPaths = new ConcurrentHashMap<>();

    /** 墓碑保留时长（毫秒）：超过后允许对账重新登记（文件若仍在）。 */
    private static final long TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * 临时录像文件「多久没被写入」即认定为孤儿（毫秒）。
     * ZLM 正在写的分片持续被追加、修改时间一直在更新，进程异常退出后才冻住。
     */
    private static final long ORPHAN_STALE_MS = 10 * 60 * 1000L;

    /** 已告警过的孤儿文件路径：对账每 60 秒跑一次，不去重会把日志淹掉。 */
    private final java.util.Set<String> warnedOrphans = ConcurrentHashMap.newKeySet();

    // ==================== 设备侧录像（RecordInfo） ====================

    /** 检索设备录像（标准 9.8），同步等待应答。 */
    public List<RecordItem> queryDeviceRecords(String channelId, String startTime,
                                               String endTime, Integer timeoutSeconds) {
        return queryDeviceRecords(channelId, channelId, startTime, endTime, timeoutSeconds);
    }

    /**
     * 检索设备录像（标准 9.8 RecordInfo）：SIP 寻址用设备编码，MANSCDP 的 {@code <DeviceID>} 用通道编码。
     * 只用一个 ID 时，下级平台 / 级联场景必然抛「设备未注册」，界面只会显示「没有录像」。
     */
    public List<RecordItem> queryDeviceRecords(String deviceId, String channelId,
                                               String startTime, String endTime,
                                               Integer timeoutSeconds) {
        return queryDeviceRecordsAnswered(deviceId, channelId, startTime, endTime,
                timeoutSeconds).items();
    }

    /**
     * 一次设备录像检索的结果。{@code deviceAnswered} 区分「没答」与「答了空列表」，
     * {@code failure} 是设备拒绝本次检索的原因（如 SIP {@code 400 ...}）。
     */
    public record DeviceRecordQuery(List<RecordItem> items, boolean deviceAnswered,
                                    String failure) {
        static DeviceRecordQuery noAnswer() {
            return new DeviceRecordQuery(List.of(), false, null);
        }

        static DeviceRecordQuery refused(String failure) {
            return new DeviceRecordQuery(List.of(), false, failure);
        }
    }

    /**
     * 检索设备录像，并明确告知设备有没有应答。
     * 「设备没应答」与「设备回了空列表」在数据上都是空列表，但处理方式相反：
     * 前者要查设备 / 网络，后者什么都不用做。
     */
    public DeviceRecordQuery queryDeviceRecordsAnswered(String deviceId, String channelId,
                                                        String startTime, String endTime,
                                                        Integer timeoutSeconds) {
        /*
         * SDK 直连设备（大华等）不走 SIP RecordInfo：它不接 MANSCDP，
         * 只能调 CLIENT_QueryRecordFile 同步拿回列表。
         * 不在这里分流的话，国标链路会一直等一个永远不会来的应答，
         * 界面表现是「检索转圈 15 秒后什么都没有」。
         */
        if (dahuaCapability.isDahua(deviceId)) {
            return querySdkRecords(deviceId, channelId, startTime, endTime);
        }
        Gb28181MediaService media = platform.mediaService();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        String key = channelId + "|" + startTime + "|" + endTime;

        /* ① 短时缓存：用户连点「检索」、在列表与时间轴间来回切，不该再发一次 SIP。 */
        CachedRecords cached = recordCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.result();
        }

        /* ② 同一窗口已在查：共用这一次查询，不重复下发 MESSAGE。 */
        CompletableFuture<DeviceRecordQuery> mine = new CompletableFuture<>();
        CompletableFuture<DeviceRecordQuery> running = recordInFlight.putIfAbsent(key, mine);
        if (running != null) {
            int wait = timeoutSeconds == null || timeoutSeconds <= 0
                    ? QUERY_TIMEOUT_SECONDS : timeoutSeconds;
            try {
                return running.get(wait + 2L, TimeUnit.SECONDS);
            } catch (Exception e) {
                return DeviceRecordQuery.noAnswer();
            }
        }

        try {
            DeviceRecordQuery result;
            /*
             * ③ 同一通道必须串行：SIP 层的应答聚合状态是按通道单一槽位
             * （recordPagers / recordCallbacks 以通道编码为键），同通道两个查询会互相覆盖回调，
             * 先发的那个永远等不到应答。界面「换通道」会同时触发当天检索与最近 15 分钟状态两次查询，
             * 不串行则每换一次通道必然产生一次假超时。
             */
            Object lock = channelLocks.computeIfAbsent(channelId, k -> new Object());
            synchronized (lock) {
                result = doQuery(media, deviceId, channelId, startTime, endTime, timeoutSeconds);
            }
            /* 只缓存设备应答过的查询：缓存失败结果会让重试立刻又拿到同一个失败。 */
            if (result.deviceAnswered()) {
                recordCache.put(key, new CachedRecords(result, System.currentTimeMillis()));
                trimRecordCache();
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.complete(DeviceRecordQuery.noAnswer());
            throw e;
        } finally {
            recordInFlight.remove(key, mine);
        }
    }

    /**
     * SDK 直连设备的录像检索（同步）。
     *
     * <p>与国标路径的本质区别：国标是「下发查询 → 等设备分多帧应答 → 聚合」，
     * 有超时、有丢帧、要处理 SumNum 不匹配；SDK 是一次同步调用直接返回完整列表，
     * 因此这里不需要超时窗口、不需要聚合、也没有「答了一半」的状态。
     *
     * @return 恒为 {@code deviceAnswered=true}（同步调用成功即代表设备答了）；
     *         设备拒绝时抛业务异常而不是返回空列表 —— 空列表与「设备拒绝」
     *         在界面上必须区分，否则用户会以为「这段没录像」
     */
    private DeviceRecordQuery querySdkRecords(String deviceId, String channelId,
                                              String startTime, String endTime) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelId);
        String start = normalizeSdkTime(startTime, false);
        String end = normalizeSdkTime(endTime, true);
        List<io.github.rurulalanana.dahua.ext.DahuaExtAccess.RecordFile> files;
        try {
            files = dahuaCapability.ext().queryRecordFile(session, sdkChannel, start, end);
        } catch (io.github.rurulalanana.dahua.ext.DahuaSdkException e) {
            /*
             * 把「设备没有录像能力」与「真故障」分开说。
             *
             * 实测 IPC（无 SD 卡 / 无本地存储）调 CLIENT_QueryRecordFile 直接失败：
             *   0x80000018 配置项/能力不存在
             *   0x8000004F 该型号不支持此查询
             * 这两种情况报「设备拒绝」就够了，不该让运维去查网络。
             * 其余码（句柄无效、超时）必须照实透出。
             */
            int code = e.sdkError();
            if (code == 0x80000018 || code == 0x8000004F) {
                throw BizException.badRequest("该型号不支持设备录像检索，或未安装存储卡："
                        + channelId + "（SDK 错误码 0x" + Integer.toHexString(code) + "）");
            }
            throw dahuaCapability.translate("录像检索", e);
        }
        List<RecordItem> items = new ArrayList<>(files.size());
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (var f : files) {
            try {
                items.add(RecordItem.ofSdkRecord(channelId, f.fileName(),
                        f.fileName() == null || f.fileName().isBlank()
                                ? "录像 " + f.start() : f.fileName(),
                        sdf.parse(f.start()), sdf.parse(f.end()), f.bytes()));
            } catch (java.text.ParseException pe) {
                // 单条时间解析失败只跳过这一条：一条坏数据不该让整次检索作废
                log.debug("SDK 录像段时间解析失败，跳过: {}", f);
            }
        }
        log.info("SDK 录像检索: device={} channel={} {} ~ {} 共 {} 段",
                deviceId, channelId, start, end, items.size());
        return new DeviceRecordQuery(items, true, null);
    }

    /**
     * 把前端传来的时间规整成 SDK 要的 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>前端可能传 {@code 2026-11-02T00:00:00}（ISO，带 T）、
     * {@code 2026-11-02 00:00:00}，或只有日期。大华只认空格分隔且必须有秒，
     * 传 T 会被判非法而返回空结果 —— <b>这种失败不报错</b>，
     * 看起来就像「这段时间没录像」，是本项目踩过的坑（见排障实录 §1.1）。
     *
     * @param endOfDay {@code true} 表示这是结束时间：只给日期时补到 23:59:59
     */
    private static String normalizeSdkTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            throw BizException.badRequest("缺少时间参数");
        }
        String s = text.trim().replace('T', ' ');
        // 去掉毫秒与时区后缀
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.length() == 10) {
            s = s + (endOfDay ? " 23:59:59" : " 00:00:00");
        } else if (s.length() == 16) {
            s = s + (endOfDay ? ":59" : ":00");
        }
        if (s.length() != 19) {
            throw BizException.badRequest("时间格式非法（要求 yyyy-MM-dd HH:mm:ss）：" + text);
        }
        return s;
    }

    /**
     * 下发 {@code RecordInfo} 并按小片轮询等待聚合结果。
     * 必须轮询而非一次 {@code future.get(timeout)}：设备拒绝时（实测 LiveGBS 回
     * {@code 400 wait for recordinfo timeout[15s]}）在自己的超时点才回话，
     * 正好落在业务超时附近，一次等待很可能抢不到原因。
     */
    private DeviceRecordQuery doQuery(Gb28181MediaService media, String deviceId,
                                      String channelId, String startTime,
                                      String endTime, Integer timeoutSeconds) {
        CompletableFuture<List<RecordItem>> future = new CompletableFuture<>();
        media.queryRecordInfo(deviceId, channelId, startTime, endTime, future::complete);
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? QUERY_TIMEOUT_SECONDS : timeoutSeconds;
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    List<RecordItem> items = future.get(WAIT_SLICE_MS, TimeUnit.MILLISECONDS);
                    cacheDeviceRecords(channelId, items);
                    return new DeviceRecordQuery(items, true, null);
                } catch (java.util.concurrent.TimeoutException slice) {
                    // 这一片没结果：看设备是否已经明确拒绝
                    String refusal = media.lastRecordFailure(channelId);
                    if (refusal != null) {
                        Gb28181MediaService.RecordDrain drain = media.drainRecordQuery(channelId);
                        log.warn("录像检索被设备拒绝: device={} channel={} 段数={} 响应={}",
                                deviceId, channelId, drain.items().size(), refusal);
                        if (!drain.items().isEmpty()) {
                            cacheDeviceRecords(channelId, drain.items());
                            return new DeviceRecordQuery(drain.items(), true, refusal);
                        }
                        return DeviceRecordQuery.refused(refusal);
                    }
                }
            }
            /*
             * 超时也把已收到的帧交上去：设备少报甚至不发 SumNum、分页帧丢包时，
             * 聚合永远等不到「收齐」，让用户看到 5 段远好于一段都没有。
             */
            Gb28181MediaService.RecordDrain drain = media.drainRecordQuery(channelId);
            List<RecordItem> partial = drain.items();
            if (!partial.isEmpty()) {
                log.info("设备录像检索超时，返回已收到的 {} 段: device={} channel={}",
                        partial.size(), deviceId, channelId);
                cacheDeviceRecords(channelId, partial);
                return new DeviceRecordQuery(partial, true, null);
            }
            /*
             * 设备在 SIP 层拒绝时把原因带上去：没有它界面只能报「设备未应答」，
             * 运维会去查平台 / 网络，而设备其实已说清原因。
             */
            String refusal = media.lastRecordFailure(channelId);
            log.warn("设备录像检索超时: device={} channel={} 收到帧={} 设备响应={}",
                    deviceId, channelId, drain.framesReceived(), refusal);
            if (refusal != null) {
                return DeviceRecordQuery.refused(refusal);
            }
            /* 收到过帧但里面一段都没有 —— 设备确实回了「没有录像」，这是有效答案。 */
            return new DeviceRecordQuery(List.of(), drain.framesReceived(), null);
        } catch (Exception e) {
            log.warn("设备录像检索失败: device={} channel={} {}",
                    deviceId, channelId, e.getMessage());
            return DeviceRecordQuery.noAnswer();
        }
    }

    /**
     * 把检索结果写入本地缓存表，便于分页查询与按天统计。
     * 唯一键用「通道 + 起始时间 + 结束时间」而非 {@code FilePath}：标准 A.2.4.4 里
     * {@code FilePath} 可选，实测 LiveGBS 与大量国产设备根本不填，用它做键会漏掉全部录像。
     */
    @Transactional
    public void cacheDeviceRecords(String channelId, List<RecordItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (RecordItem item : items) {
            if (item.getStartTime() == null) {
                // 连起始时间都没有的条目无法定位也无法回放，跳过
                continue;
            }
            LocalDateTime start = TimeFormats.of(item.getStartTime());
            LocalDateTime end = TimeFormats.of(item.getEndTime());
            GbDeviceRecord existing = deviceRecordMapper.selectOne(
                    Wrappers.<GbDeviceRecord>lambdaQuery()
                            .eq(GbDeviceRecord::getChannelId, channelId)
                            .eq(GbDeviceRecord::getStartTime, start)
                            .eq(end != null, GbDeviceRecord::getEndTime, end)
                            .last("limit 1"));
            GbDeviceRecord record = existing == null ? new GbDeviceRecord() : existing;
            record.setChannelId(channelId);
            record.setDeviceId(item.getDeviceId());
            record.setName(item.getName());
            record.setFilePath(item.getFilePath());
            record.setAddress(item.getAddress());
            record.setStartTime(start);
            record.setEndTime(end);
            record.setType(item.getType());
            record.setSize(item.getFileSize());
            if (record.getStartTime() != null && record.getEndTime() != null) {
                record.setDuration((int) java.time.Duration.between(
                        record.getStartTime(), record.getEndTime()).getSeconds());
            }
            record.setUpdateTime(LocalDateTime.now());
            if (record.getId() == null) {
                deviceRecordMapper.insert(record);
            } else {
                deviceRecordMapper.updateById(record);
            }
        }
    }

    /**
     * 本地缓存的设备录像查询，区间语义是「有交集」。
     * 录像段常比查询窗口长（如 08:00~10:00 的一段，用户查 09:00），
     * 用 {@code start_time BETWEEN from AND to} 会判不出这段录像。
     * 另需兼容 {@code end_time} 为空的单帧记录（退化为起点在区间内）。
     */
    public List<GbDeviceRecord> deviceRecords(String channelId, LocalDateTime from,
                                              LocalDateTime to, String type) {
        return deviceRecordMapper.selectList(Wrappers.<GbDeviceRecord>lambdaQuery()
                .eq(channelId != null && !channelId.isBlank(),
                        GbDeviceRecord::getChannelId, channelId)
                .eq(type != null && !type.isBlank() && !"all".equalsIgnoreCase(type),
                        GbDeviceRecord::getType, type)
                // 录像段起点不能晚于查询结束
                .le(to != null, GbDeviceRecord::getStartTime, to)
                // 录像段终点不能早于查询起点（end_time 为空时退化为起点判断）
                .and(from != null, w -> w
                        .ge(GbDeviceRecord::getEndTime, from)
                        .or(x -> x.isNull(GbDeviceRecord::getEndTime)
                                .ge(GbDeviceRecord::getStartTime, from)))
                .orderByDesc(GbDeviceRecord::getStartTime));
    }

    /** 按天查询设备录像（用于 {@code querydaily}）。 */
    public List<GbDeviceRecord> deviceRecordsOfDay(String channelId, String yyyymmdd) {
        LocalDateTime day = TimeFormats.parse(yyyymmdd);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + yyyymmdd);
        }
        return deviceRecords(channelId, day, day.plusDays(1).minusSeconds(1), null);
    }

    // ==================== 云端录像 ====================

    /**
     * 开始平台侧录像。
     * ZLM 的 {@code mk_recorder_start} 按 {@code app/stream} 查找媒体源，源不存在直接失败
     * （{@code 未找到相关的MediaSource}），所以流未建立时先自动点播并等 ZLM 真正收到 RTP 包。
     */
    @Transactional
    public GbRecord startCloudRecord(String deviceId, String channelId) {
        if (platform.recordingService() == null) {
            throw BizException.badRequest("录像服务未启动");
        }
        ensureStreamReady(deviceId, channelId);
        Recording recording = platform.recordingService().startRecording(channelId);
        // 刚开始录制时文件还没落盘，不需要（也没法）上传
        return persistRecording(recording);
    }

    /**
     * 直接开始录像，不点播、不等流，专供流上线回调使用
     * （此时流刚就绪，再走 {@link #startCloudRecord} 的点播 + 轮询会重复 INVITE）。
     * 落库由归档路径统一处理。
     *
     * @return ZLM 录像条目；服务未启动返回 null
     */
    public Recording startRecordingDirect(String channelId) {
        if (platform.recordingService() == null) {
            return null;
        }
        return platform.recordingService().startRecording(channelId);
    }

    /**
     * 确保流已建立：未建立则点播，并等待 ZLM 收到 RTP 包。
     * 判据用「ZLM 是否收到过 RTP」而非「会话是否存在」：会话在 INVITE 发出后立刻就有，
     * 但设备要几百毫秒到数秒后才推流，期间媒体源尚未注册，录制仍会失败。
     */
    private void ensureStreamReady(String deviceId, String channelId) {
        Gb28181MediaService media = platform.mediaService();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        if (isRtpReceiving(channelId)) {
            return;
        }
        // 未建立：先点播（已存在会话时 StreamService 会直接复用，不会重复 INVITE）
        streamService.startLive(deviceId, channelId);
        long deadline = System.currentTimeMillis() + STREAM_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isRtpReceiving(channelId)) {
                return;
            }
            try {
                Thread.sleep(STREAM_READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BizException.badRequest("等待流建立超时（"
                + (STREAM_READY_TIMEOUT_MS / 1000) + " 秒），无法开始录像。"
                + "请确认设备在线且点播正常（通道 " + channelId + "）");
    }

    /** ZLM 是否已收到该通道的 RTP 包（媒体源已注册）。 */
    private boolean isRtpReceiving(String channelId) {
        ZlmMediaServer mediaServer = platform.mediaServer();
        RtpSessionManager sessions = platform.sessionManager();
        if (mediaServer == null || sessions == null) {
            return false;
        }
        RtpSession session = sessions.getByChannel(channelId);
        if (session == null) {
            return false;
        }
        // 用 ZLM 实际坐标探测：单端口模式下 app/stream 与业务坐标不同
        return mediaServer.isRtpReceiving(session.getZlmApp(), session.getStream());
    }

    /**
     * 停止平台侧录像并归档。启用对象存储时异步上传 MP4（大文件不阻塞接口），
     * 上传结果回写 {@code storage_type/object_key/file_url/upload_state}。
     */
    @Transactional
    public GbRecord stopCloudRecord(String channelId) {
        if (platform.recordingService() == null) {
            return null;
        }
        Recording recording = platform.recordingService().stopRecording(channelId);
        if (recording == null) {
            return null;
        }
        // 停机 / 秒停时 ZLM 可能已抢先停录，回调没送到，此时 filePath 为 null。
        // 这种条目入库就是坏记录（file_path 空、size=0，前端播放会 Path.of(null) 抛 NPE），
        // 返回 null 交给归档的磁盘兜底路径。
        if (recording.getFilePath() == null || recording.getFilePath().isBlank()) {
            log.warn("停止录像但未拿到文件路径，跳过入库（交给磁盘对账补登记）: channel={}",
                    channelId);
            return null;
        }
        return persistAndUpload(recording);
    }

    /**
     * 异步上传录像到对象存储。成功后按配置决定是否删本地文件，
     * 失败只置 {@code upload_state=FAILED}，本地文件保留。
     * 调用方在事务内时必须等提交后再启动，否则异步线程读不到刚写入的记录。
     */
    private void uploadAsync(String recordId, String filePath, String channelId) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        Runnable task = () -> {
            try {
                Path path = Path.of(filePath);
                if (!Files.exists(path)) {
                    log.warn("录像文件不存在，跳过上传：{}", filePath);
                    markUpload(recordId, "FAILED", null, null, 0L);
                    return;
                }
                ObjectStorageService.UploadResult result = storage.upload(path, channelId);
                markUpload(recordId, "UPLOADED", result.getObjectKey(),
                        result.getUrl(), result.getSize());
                log.info("录像已上传对象存储: recordId={} key={}", recordId, result.getObjectKey());
                if (storageProps.isDeleteLocalAfterUpload()) {
                    try {
                        Files.deleteIfExists(path);
                        log.info("已删除本地录像文件（上传成功）: {}", filePath);
                    } catch (Exception e) {
                        log.warn("删除本地录像文件失败: {} {}", filePath, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("录像上传对象存储失败: recordId={} {}", recordId, e.getMessage());
                markUpload(recordId, "FAILED", null, null, 0L);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    CompletableFuture.runAsync(task);
                                }
                            });
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    private void markUpload(String recordId, String state, String objectKey,
                            String fileUrl, long size) {
        try {
            GbRecord record = findRecord(recordId);
            if (record == null) {
                return;
            }
            record.setUploadState(state);
            if (objectKey != null) {
                record.setObjectKey(objectKey);
            }
            if (fileUrl != null) {
                record.setFileUrl(fileUrl);
            }
            if (size > 0) {
                record.setFileSize(size);
            }
            if ("UPLOADED".equals(state)) {
                record.setUploadTime(LocalDateTime.now());
                record.setStorageType(storage.type());
            }
            recordMapper.updateById(record);
        } catch (Exception e) {
            log.warn("回写上传状态失败: recordId={} {}", recordId, e.getMessage());
        }
    }

    /**
     * 自动录像对账：扫描录像目录，把「磁盘有、库里无」的文件补登记。
     * 不读 ZLM 内存索引：实测流下线时停录与索引簿记有竞态，条目可能先被摘除，
     * 导致 {@code stopRecording} 拿不到条目、终态永不归档。磁盘文件是最终事实。
     * 目录布局：{@code <recordRoot>/<channelId>/record/<app>/<zlmStream>/<yyyy-MM-dd>/*-<idx>.mp4}。
     *
     * @return 本次补登记的文件数
     */
    public int reconcileAutoRecordings() {
        File rootDir = new File(props.resolvedRecordRoot());
        if (!rootDir.isDirectory()) {
            return 0;
        }
        int count = 0;
        File[] channelDirs = rootDir.listFiles(File::isDirectory);
        if (channelDirs == null) {
            return 0;
        }
        // 【性能】一次性读出所有已入库路径，而不是每文件查一次库：
        // 本任务每 60 秒跑一次，1000 个文件就是每分钟 1000 条 SQL，会占满连接池。
        Set<String> known = loadKnownFilePaths();
        for (File channelDir : channelDirs) {
            String channelId = channelDir.getName();
            /*
             * 跳过 {@code <设备编码>/<通道编码>_playback} 这类目录：
             * 它们是设备录像下载的产物，当作通道登记会在云端录像列表里凭空多出假通道。
             */
            if (isTransientDir(channelId)) {
                continue;
            }
            List<File> files = new ArrayList<>();
            collectMp4s(channelDir, files);
            for (File f : files) {
                // 正在写的分片是临时名（ZLM 用 . 前缀，关闭后改名）：跳过
                if (f.getName().startsWith(".")) {
                    warnOrphanIfStale(channelId, f);
                    continue;
                }
                String absPath = f.getAbsolutePath();
                // 用户主动删过的路径不得复活（删除时文件可能没删掉）
                if (isDeleted(absPath)) {
                    continue;
                }
                if (known.contains(absPath)) {
                    continue;
                }
                try {
                    saveRecord(toDiskRecord(channelId, f));
                    count++;
                    log.info("对账补登记录像: channel={} file={}", channelId, absPath);
                } catch (Exception e) {
                    log.warn("对账登记失败: file={} {}", absPath, e.getMessage());
                }
            }
        }
        return count;
    }

    /** 该目录名是否是「回放/下载」这类临时产物目录（不参与云端录像对账）。 */
    private static boolean isTransientDir(String name) {
        if (name == null) {
            return true;
        }
        return name.endsWith("_playback") || name.endsWith("_download");
    }

    /**
     * 对「长时间未收尾的临时录像」告警，只告警不删除。
     * ZLM 写入中的分片带 {@code .} 前缀，正常关闭后改名；进程被强杀 / ZLM 原生崩溃时
     * 改名与 moov 回写来不及做，留下大小正常但没有 moov 的文件，任何播放器都打不开。
     * 判据用修改时间而不是前缀：录制中的分片同样带前缀，但修改时间一直在更新。
     */
    private void warnOrphanIfStale(String channelId, File f) {
        long age = System.currentTimeMillis() - f.lastModified();
        if (age < ORPHAN_STALE_MS) {
            return;
        }
        // 按路径去重：对账每 60 秒跑一次，不去重会每分钟刷一条同样的告警
        if (warnedOrphans.add(f.getAbsolutePath())) {
            log.warn("发现未收尾的录像临时文件（进程异常退出，无法播放，需人工清理）: "
                            + "channel={} file={} size={}MB 最后写入={} 分钟前",
                    channelId, f.getAbsolutePath(), f.length() / 1048576,
                    age / 60000);
        }
    }

    /**
     * 一次性读出库里所有已登记的录像文件路径（对账用）。
     * 对账是全集比对，按通道查仍是 N 次 SQL；只 select 路径字段，不查整行。
     * 10 万条约 10MB，以 60 秒一次的频率可接受；百万级应改为只取最近 N 天。
     */
    private Set<String> loadKnownFilePaths() {
        Set<String> out = new java.util.HashSet<>();
        try {
            List<Object> paths = recordMapper.selectObjs(Wrappers.<GbRecord>lambdaQuery()
                    .select(GbRecord::getFilePath)
                    .isNotNull(GbRecord::getFilePath));
            for (Object p : paths) {
                if (p != null) {
                    out.add(p.toString());
                }
            }
        } catch (Exception e) {
            // 查不到就退回「全部当作未登记」—— 会多补几条，但由
            // uk_gb_record_file_path 唯一索引兜底，不会产生重复记录
            log.warn("读取已登记录像路径失败，本次对账退化为逐文件判断: {}", e.getMessage());
        }
        return out;
    }

    private void collectMp4s(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collectMp4s(f, out);
            } else if (f.getName().toLowerCase().endsWith(".mp4")) {
                out.add(f);
            }
        }
    }

    /** 从磁盘文件构建录像条目（时间从文件名 / 修改时间推断，文件名格式见 ZLM Mp4.cpp）。 */
    private GbRecord toDiskRecord(String channelId, File f) {
        GbRecord r = new GbRecord();
        r.setRecordId(java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16));
        r.setChannelId(channelId);
        // device_id 必须是设备编码而非通道编码：对账只有目录名（= 通道编码），
        // 需按 gb_channel 反查；查不到退回通道编码（直连场景二者相同），至少保证非空。
        r.setDeviceId(deviceIdOf(channelId));
        r.setName(channelId);
        r.setFilePath(f.getAbsolutePath());
        r.setFileName(f.getName());
        LocalDateTime end = lastModified(f);
        // 文件名形如 2026-10-16-10-36-18-0.mp4，前 19 字符即开始时间；解析失败用修改时间兜底
        LocalDateTime start = parseZlmFileName(f.getName());
        if (start == null || start.isAfter(end)) {
            start = end;
        }
        r.setStartTime(start);
        // 对账时录制早已结束：以最后修改时间近似结束时间（时长 = 两者差）
        r.setEndTime(end);
        r.setDuration((int) Duration.between(start, end).getSeconds());
        r.setFileSize(f.length());
        r.setCloudRecord(true);
        r.setImportant(false);
        r.setCreateTime(LocalDateTime.now());
        r.setStorageType(ObjectStorageService.TYPE_LOCAL);
        r.setUploadState(storage.enabled() ? "PENDING" : "NONE");
        return r;
    }

    /** 文件最后修改时间（读失败时回退到当前时间，不让对账中断）。 */
    private LocalDateTime lastModified(File f) {
        try {
            return LocalDateTime.ofInstant(
                    java.nio.file.Files.getLastModifiedTime(f.toPath()).toInstant(),
                    java.time.ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /**
     * 按通道编码反查设备编码。多通道设备下二者不同（通道 {@code 34020000001320000001}
     * 属于设备 {@code 34020000002000000006}），直接写通道编码会让「按设备查录像」漏记录。
     */
    private String deviceIdOf(String channelId) {
        try {
            io.github.rurulalanana.starter.entity.GbChannel channel =
                    channelMapper.selectOne(Wrappers
                            .<io.github.rurulalanana.starter.entity.GbChannel>lambdaQuery()
                            .eq(io.github.rurulalanana.starter.entity.GbChannel::getChannelId,
                                    channelId)
                            .last("limit 1"));
            if (channel != null && channel.getDeviceId() != null
                    && !channel.getDeviceId().isBlank()) {
                return channel.getDeviceId();
            }
        } catch (Exception e) {
            log.debug("反查设备编码失败，退回通道编码: channel={} {}", channelId, e.getMessage());
        }
        return channelId;
    }

    /** 解析 ZLM 录像文件名中的时间（{@code yyyy-MM-dd-HH-mm-ss-<idx>.mp4}）。 */
    private LocalDateTime parseZlmFileName(String name) {
        try {
            String base = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
            String[] p = base.split("-");
            if (p.length < 6) {
                return null;
            }
            return LocalDateTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]));
        } catch (Exception e) {
            return null;
        }
    }

    /** 云端录像是否正在录制。 */
    public boolean isRecording(String channelId) {
        return platform.recordingService() != null
                && platform.recordingService().isRecording(channelId);
    }

    /**
     * 归档一条已结束的录像（自动录像的收尾），索引优先、磁盘兜底。
     * 必须有兜底：实测停止录像时 ZLM 索引条目的 {@code endTime} 长期为 0（连续轮询 31 次不变），
     * 而文件早已落盘可播，只认索引会让录像要等 60 秒对账才出现。
     * 不走 {@link #stopCloudRecord}：流下线时 ZLM 已自动停录，再调 {@code mk_recorder_stop} 只会拿到 null。
     *
     * @return 归档成功返回库里的条目；无可归档内容返回 null
     */
    public GbRecord archiveRecording(String channelId) {
        return archiveRecording(channelId, 0L);
    }

    /**
     * 归档一条已结束的录像，只认「不早于 {@code sinceMillis} 开始」的条目。
     * ZLM 录像索引是累积的，不加时间窗口会命中第一个已结束条目 —— 实测回放会话关闭时
     * 就拿到上一次直播的录像重复入库。
     *
     * @param sinceMillis 只归档开始时间不早于此毫秒的条目；{@code 0} 表示不限
     * @return 归档成功返回库里的条目；无可归档内容返回 null
     */
    public GbRecord archiveRecording(String channelId, long sinceMillis) {
        if (platform.recordingService() == null) {
            return null;
        }
        // 路径一：ZLM 内存索引的终态条目
        Recording recording = awaitTerminalRecording(channelId, sinceMillis);
        // 索引条目可能「已结束但没有文件路径」：停机时 ZLM 抢先自动停录并落盘，
        // 回调没送到，endTime 有了但 filePath 仍是 null。入库就是坏记录（file_path 空、size=0），
        // 且会与随后对账补登记的正确记录重复（实测同一文件两条）。无路径时落到磁盘补登记。
        if (recording != null && recording.getFilePath() != null
                && !recording.getFilePath().isBlank()) {
            return persistAndUpload(recording);
        }
        // 路径二：索引不可用（或索引条目缺文件路径），扫该通道目录补登记
        return archiveFromDisk(channelId, sinceMillis);
    }

    /**
     * 从磁盘补登记该通道最新一个尚未入库的录像文件（归档场景只关心刚结束那一段）。
     * 判据与 {@link #reconcileAutoRecordings} 一致：跳过 {@code .} 前缀的写入中分片、墓碑、已入库。
     *
     * @param sinceMillis 只登记修改时间不早于此毫秒的文件；0 表示不限
     */
    private GbRecord archiveFromDisk(String channelId, long sinceMillis) {
        File channelDir = new File(props.resolvedRecordRoot(), channelId);
        if (!channelDir.isDirectory()) {
            return null;
        }
        List<File> files = new ArrayList<>();
        collectMp4s(channelDir, files);
        // 按修改时间倒序：最新结束的那段就是本次要归档的
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (f.getName().startsWith(".")) {
                warnOrphanIfStale(channelId, f);
                continue;
            }
            // 早于本次会话开始的文件是历史录像，不归本次归档负责
            if (sinceMillis > 0 && f.lastModified() < sinceMillis) {
                continue;
            }
            String absPath = f.getAbsolutePath();
            if (isDeleted(absPath)) {
                continue;
            }
            Long exists = recordMapper.selectCount(Wrappers.<GbRecord>lambdaQuery()
                    .eq(GbRecord::getFilePath, absPath));
            if (exists != null && exists > 0) {
                continue;
            }
            try {
                GbRecord record = toDiskRecord(channelId, f);
                record.setStorageType(storage.type());
                record.setUploadState(storage.enabled() ? "PENDING" : "NONE");
                saveRecord(record);
                if (storage.enabled()) {
                    uploadAsync(record.getRecordId(), absPath, channelId);
                }
                log.info("归档录像（磁盘补登记）: channel={} file={}", channelId, absPath);
                return record;
            } catch (Exception e) {
                log.warn("归档磁盘登记失败: file={} {}", absPath, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 等待 ZLM 内存索引里出现「已结束」的录像条目。
     * 停录由 ZLM 回调线程异步完成，归档在「停止直播」接口线程上同步执行，两者存在竞态：
     * 归档跑在前面时 {@code endTime} 仍为 0，本次归档会静默失败（实测需等 60 秒对账才出现）。
     * 等待有界：索引里压根没有该通道条目时立即返回 null。
     *
     * @param sinceMillis 只认开始时间不早于此毫秒的条目；0 表示不限
     * @return 已结束的录像条目；超时或不存在返回 {@code null}
     */
    private Recording awaitTerminalRecording(String channelId, long sinceMillis) {
        long deadline = System.currentTimeMillis() + ARCHIVE_WAIT_MS;
        while (true) {
            List<Recording> list = platform.recordingService().recordings(channelId);
            for (Recording r : list) {
                // 必须同时满足「已结束」与「属于本次会话」：
                // 索引是累积的，只看 endTime>0 会反复归档历史条目
                if (r.getEndTime() > 0 && r.getStartTime() >= sinceMillis
                        && !isRegistered(r.getRecordId())) {
                    return r;
                }
            }
            // 没有条目 = 该通道未在录像，无需等待
            if (list.isEmpty() || System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(ARCHIVE_WAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 该 ZLM 录像条目是否已入库（避免重复归档同一段录像）。 */
    private boolean isRegistered(String recordId) {
        if (recordId == null) {
            return false;
        }
        Long n = recordMapper.selectCount(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, recordId));
        return n != null && n > 0;
    }

    /**
     * 云端录像列表：以 {@code gb_record} 表为底，再用 ZLM 内存索引覆盖「录制中」条目、
     * 补齐库中缺失的新录像。
     * 去重必须按文件路径而不是 recordId：内存条目与库条目各自生成随机 recordId，
     * 按 ID 比对永远不相等，同一段录像会以「录制中 + 已完成」两条出现。
     */
    public List<GbRecord> cloudRecords(String channelId, LocalDateTime from, LocalDateTime to) {
        List<GbRecord> out = new ArrayList<>(recordMapper.selectList(
                Wrappers.<GbRecord>lambdaQuery()
                        .eq(channelId != null && !channelId.isBlank(),
                                GbRecord::getChannelId, channelId)
                        .ge(from != null, GbRecord::getStartTime, from)
                        .lt(to != null, GbRecord::getStartTime, to)
                        .orderByAsc(GbRecord::getStartTime)));
        // 用内存索引覆盖「录制中」条目，并补齐库里还没有的新录像
        if (platform.recordingService() != null) {
            // 按文件路径索引库里已有的条目
            java.util.Map<String, GbRecord> byPath = new java.util.LinkedHashMap<>();
            for (GbRecord r : out) {
                if (r.getFilePath() != null && !r.getFilePath().isBlank()) {
                    byPath.put(r.getFilePath(), r);
                }
            }
            for (Recording r : platform.recordingService().search(channelId,
                    from == null ? null : TimeFormats.toDate(from).getTime(),
                    to == null ? null : TimeFormats.toDate(to).getTime())) {
                GbRecord live = toRecord(r);
                // 路径为空说明分片还没落盘（刚开始录），没什么可展示的，
                // 等下一次轮询（首次落盘通常几秒内）
                if (live.getFilePath() == null || live.getFilePath().isBlank()) {
                    continue;
                }
                GbRecord existing = byPath.get(live.getFilePath());
                if (existing == null) {
                    out.add(live);
                    byPath.put(live.getFilePath(), live);
                } else {
                    // 同一文件：录制中的条目以内存为准（结束时间/时长实时变化）
                    if (existing.getEndTime() == null) {
                        existing.setEndTime(live.getEndTime());
                        existing.setDuration(live.getDuration());
                        existing.setFileSize(live.getFileSize());
                    }
                }
            }
            out.sort(java.util.Comparator.comparing(GbRecord::getStartTime,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            // 追加「正在写的这一段」占位条目
            appendInProgressEntry(channelId, out, to);
        }
        return out;
    }

    /**
     * 追加一条「正在写的分片」占位条目（{@code endTime == null} 即界面上的「录制中」）。
     * ZLM 只在分片写完时才回调 {@code on_mk_record_mp4}，两段之间列表里完全看不出还在录。
     * 占位条目的 {@code filePath} 必须留空：写入中的文件带 {@code .} 前缀、没有 moov、无法播放。
     */
    private void appendInProgressEntry(String channelId, List<GbRecord> out, LocalDateTime to) {
        if (channelId == null || channelId.isBlank() || out.isEmpty()) {
            return;
        }
        if (!isRecording(channelId)) {
            return;
        }
        // 以列表里最后一条（最新）为基准推算当前段的起点
        GbRecord last = out.get(out.size() - 1);
        LocalDateTime from = last.getEndTime() != null
                ? last.getEndTime() : last.getStartTime();
        if (from == null) {
            return;
        }
        // 上一条如果本身就是占位（连续两次轮询都还没轮转），不要叠加
        if (last.getEndTime() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (from.isAfter(now)) {
            from = now;
        }
        GbRecord live = new GbRecord();
        // recordId 固定成可辨识的前缀：前端点播时必然 404（这不是真实条目），
        // 但正常路径会被 recording=true 拦在前面
        live.setRecordId("live-" + channelId);
        live.setChannelId(channelId);
        live.setDeviceId(deviceIdOf(channelId));
        live.setName(channelId);
        live.setStartTime(from);
        // endTime 留空 = 录制中（CloudRecordController 据此置 recording=true）
        live.setDuration((int) Duration.between(from, now).getSeconds());
        live.setFileSize(0L);
        live.setCloudRecord(true);
        live.setImportant(false);
        live.setCreateTime(now);
        live.setStorageType(ObjectStorageService.TYPE_LOCAL);
        live.setUploadState("NONE");
        // 超出查询窗口就不显示（用户在看昨天的记录时不该出现今天的「正在录」）
        if (to != null && !from.isBefore(to)) {
            return;
        }
        out.add(live);
    }

    /** 云端录像点播：把 MP4 拉入 ZLM 转协议输出。 */
    public PlayUrls playCloudRecord(String recordId) {
        if (platform.recordingService() == null) {
            throw BizException.badRequest("录像服务未启动");
        }
        Recording recording = platform.recordingService().get(recordId);
        if (recording == null) {
            // 内存索引重启后为空：用库里的 file_path 重新登记后再点播
            GbRecord record = findRecord(recordId);
            if (record == null) {
                throw BizException.notFound("录像不存在：" + recordId);
            }
            if (!ObjectStorageService.TYPE_LOCAL.equals(record.getStorageType())) {
                throw BizException.badRequest("对象存储中的录像请使用下载地址播放："
                        + record.getFileUrl());
            }
            recording = platform.recordingService().register(record.getRecordId(),
                    record.getChannelId(), record.getFilePath(),
                    record.getStartTime() == null ? System.currentTimeMillis()
                            : TimeFormats.toDate(record.getStartTime()).getTime());
            if (recording == null) {
                throw BizException.badRequest("录像文件已丢失：" + record.getFilePath());
            }
        }
        PlayUrls urls = platform.recordingService().playUrl(recording);
        if (urls == null) {
            throw BizException.badRequest("录像文件不存在或点播失败：" + recording.getFilePath());
        }
        return urls;
    }

    /** 停止云端录像点播。 */
    public void stopCloudRecordPlay(String recordId) {
        if (platform.recordingService() != null) {
            platform.recordingService().stopPlay(recordId);
        }
    }

    /** 录像的可播放/下载地址：对象存储优先用签名或外链，本地回退到平台下载接口。 */
    public String downloadUrl(GbRecord record) {
        if (record == null) {
            return null;
        }
        if (inObjectStorage(record)) {
            String url = storage.url(record.getObjectKey(),
                    Duration.ofSeconds(3600));
            if (url != null) {
                return url;
            }
            return record.getFileUrl();
        }
        return "/api/v1/cloudrecord/file/" + record.getRecordId();
    }

    /** 录像文件是否可用（本地文件存在，或对象存储中已上传）。 */
    public boolean fileAvailable(GbRecord record) {
        if (record == null) {
            return false;
        }
        if (inObjectStorage(record)) {
            return true;
        }
        return record.getFilePath() != null && Files.exists(Path.of(record.getFilePath()));
    }

    /**
     * 该录像是否已归档到对象存储（且拿得到 objectKey）。
     * 只判 {@code storage_type} 不够：上传失败时 {@code object_key} 为空，仍应回退本地路径。
     * 下载地址 / 可用性 / 删除三个调用点必须用同一判据，否则会出现「能播但删不掉」。
     */
    private static boolean inObjectStorage(GbRecord record) {
        return ObjectStorageService.TYPE_OSS.equals(record.getStorageType())
                && record.getObjectKey() != null && !record.getObjectKey().isBlank();
    }

    /** 删除录像文件（本地磁盘或对象存储，按 storage_type 分派）。 */
    public void deleteFile(GbRecord record) {
        if (record == null) {
            return;
        }
        // 先立墓碑：无论文件删除成功与否，都表示「这条录像是用户主动删的」，
        // 对账不得把它重新登记回来（见 deletedPaths 注释）。
        String tombPath = record.getFilePath();
        if (tombPath != null && !tombPath.isBlank()) {
            rememberDeleted(tombPath);
        }
        if (inObjectStorage(record)) {
            storage.delete(record.getObjectKey());
            // 本地副本可能按配置保留或已删除，尽力清理
        }
        String path = record.getFilePath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (Exception e) {
            log.warn("删除本地录像文件异常: {} {}", path, e.getMessage());
        }
    }

    /** 记录一条删除墓碑（对账时跳过该路径）。 */
    private void rememberDeleted(String filePath) {
        purgeExpiredTombstones();
        deletedPaths.put(filePath, System.currentTimeMillis());
    }

    /** 该路径是否在墓碑有效期内（用户主动删过）。 */
    private boolean isDeleted(String filePath) {
        Long at = deletedPaths.get(filePath);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > TOMBSTONE_TTL_MS) {
            deletedPaths.remove(filePath, at);
            return false;
        }
        return true;
    }

    /** 清理过期墓碑，避免长期运行后无限增长。 */
    private void purgeExpiredTombstones() {
        long now = System.currentTimeMillis();
        deletedPaths.entrySet().removeIf(e -> now - e.getValue() > TOMBSTONE_TTL_MS);
    }

    /** 按录像 ID 查云端录像。 */
    public GbRecord findRecord(String recordId) {
        return recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, recordId).last("limit 1"));
    }

    /**
     * 删除云端录像记录（不删文件）。必须同时清 ZLM 内存索引：
     * {@code cloudRecords} 会合并索引条目，只删库会让录像刷新后依然出现。
     */
    public boolean removeRecord(String recordId) {
        if (platform.recordingService() != null) {
            platform.recordingService().forget(recordId);
        }
        return recordMapper.delete(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, recordId)) > 0;
    }

    /**
     * 按保留天数清理过期录像（删库 + 删文件），跳过 {@code important=true}。
     * 必须有：单通道 800 kbps 约 350 MB/小时 ≈ 8 GB/天，实测服务器只有 30G 盘，
     * 盘满后 ZLM 写不进分片、同盘的数据库也可能写失败，整个平台瘫痪。
     * 先删文件再删库：反过来文件删失败就成了没人管、也不会再被对账登记的孤儿。
     *
     * @param retainDays 保留天数；{@code <= 0} 表示不限（只受磁盘水位约束）
     * @return 清理掉的录像条数
     */
    public int purgeExpiredRecords(int retainDays) {
        if (retainDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        List<GbRecord> expired = recordMapper.selectList(Wrappers.<GbRecord>lambdaQuery()
                .lt(GbRecord::getStartTime, cutoff)
                .eq(GbRecord::getImportant, false)
                .orderByAsc(GbRecord::getStartTime));
        int n = 0;
        for (GbRecord r : expired) {
            try {
                deleteFile(r);
                removeRecord(r.getRecordId());
                n++;
            } catch (Exception e) {
                log.warn("清理过期录像失败: recordId={} file={} {}", 
                        r.getRecordId(), r.getFilePath(), e.getMessage());
            }
        }
        return n;
    }

    /**
     * 磁盘水位兜底：删最老的录像直到剩余空间占比达标。
     * 与 {@link #purgeExpiredRecords} 互补：保留天数保证「至少留 N 天」，本方法保证「盘不能满」。
     * 永远跳过 {@code important=true} 与正在录制的分片。
     *
     * @param maxUsedPercent 磁盘使用率上限（如 85 表示超过 85% 开始删）
     * @param keepMinDays    无论如何至少保留最近这么多天
     * @return 清理掉的录像条数
     */
    public int purgeOldestUntilFree(String recordRoot, int maxUsedPercent, int keepMinDays) {
        if (maxUsedPercent <= 0 || recordRoot == null || recordRoot.isBlank()) {
            return 0;
        }
        File root = new File(recordRoot);
        if (!root.isDirectory()) {
            return 0;
        }
        // 目录所在文件系统的整体水位（录像和数据库通常同盘，必须看整盘）
        long total;
        long usable;
        try {
            total = root.getTotalSpace();
            usable = root.getUsableSpace();
        } catch (Exception e) {
            log.debug("读取磁盘水位失败: {}", e.getMessage());
            return 0;
        }
        if (total <= 0) {
            return 0;
        }
        long usedPercent = (total - usable) * 100 / total;
        if (usedPercent < maxUsedPercent) {
            return 0;
        }
        log.warn("录像磁盘水位告急：已用 {}%（阈值 {}%），开始清理最老的录像",
                usedPercent, maxUsedPercent);
        LocalDateTime protectAfter = LocalDateTime.now().minusDays(Math.max(keepMinDays, 1));
        int n = 0;
        // 每批 50 条，边删边重算水位：避免一次性把全部记录读进内存
        while (true) {
            List<GbRecord> batch = recordMapper.selectList(Wrappers.<GbRecord>lambdaQuery()
                    .eq(GbRecord::getImportant, false)
                    .lt(GbRecord::getStartTime, protectAfter)
                    .orderByAsc(GbRecord::getStartTime)
                    .last("limit 50"));
            if (batch.isEmpty()) {
                log.warn("磁盘清理已无更多可删的录像（重要录像与最近 {} 天均受保护），"
                        + "当前水位 {}%", keepMinDays, usedPercent);
                return n;
            }
            for (GbRecord r : batch) {
                try {
                    deleteFile(r);
                    removeRecord(r.getRecordId());
                    n++;
                } catch (Exception e) {
                    log.warn("磁盘清理删除失败: recordId={} {}", r.getRecordId(), e.getMessage());
                }
            }
            // 重算水位，达标就停
            usable = root.getUsableSpace();
            usedPercent = (total - usable) * 100 / total;
            if (usedPercent < maxUsedPercent) {
                log.info("磁盘清理完成：本次删除 {} 条录像，水位降到 {}%", n, usedPercent);
                return n;
            }
        }
    }

    /** 设置重要标记。 */
    public boolean setImportant(String recordId, boolean important) {
        GbRecord record = findRecord(recordId);
        if (record == null) {
            return false;
        }
        record.setImportant(important);
        return recordMapper.updateById(record) > 0;
    }

    /**
     * 按录像开始时间定位（GBS 的 {@code period} 参数即开始时间）。
     * 必须按秒区间匹配：{@code period} 只精确到秒，而库里的 {@code start_time} 带毫秒，
     * 直接 eq 永远匹配不上，{@code remove} / {@code setimportant} 会一律报「录像不存在」。
     */
    public GbRecord findByPeriod(String channelId, String period) {
        LocalDateTime start = TimeFormats.parse(period);
        if (start == null) {
            throw BizException.badRequest("时间格式应为 YYYYMMDDHHmmss：" + period);
        }
        return recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId)
                .ge(GbRecord::getStartTime, start)
                .lt(GbRecord::getStartTime, start.plusSeconds(1))
                .last("limit 1"));
    }

    /** 删除某通道的全部云端录像记录（同时清该通道的内存索引，否则列表仍会合并出已删条目）。 */
    public int removeChannelRecords(String channelId) {
        // 先清内存索引：与 removeRecord 同理，否则列表仍会合并出已删条目
        if (platform.recordingService() != null) {
            for (Recording r : platform.recordingService().recordings(channelId)) {
                platform.recordingService().forget(r.getRecordId());
            }
        }
        return recordMapper.delete(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId));
    }

    /** 删除某通道某天的云端录像记录。 */
    public int removeDailyRecords(String channelId, String yyyymmdd) {
        LocalDateTime day = TimeFormats.parse(yyyymmdd);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + yyyymmdd);
        }
        // 先清内存索引：与 removeRecord 同理，否则列表仍会合并出已删条目
        if (platform.recordingService() != null) {
            LocalDateTime end = day.plusDays(1);
            for (Recording r : platform.recordingService().recordings(channelId)) {
                LocalDateTime start = TimeFormats.ofEpochMilli(r.getStartTime());
                if (!start.isBefore(day) && start.isBefore(end)) {
                    platform.recordingService().forget(r.getRecordId());
                }
            }
        }
        return recordMapper.delete(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId)
                .ge(GbRecord::getStartTime, day)
                .lt(GbRecord::getStartTime, day.plusDays(1)));
    }

    /**
     * 云端录像「有录像标记」（GBS 的 {@code queryflags} / {@code querymonthly}）。
     *
     * @param month {@code YYYYMM}；为空表示统计全部
     * @return 长度为当月天数的字符串，{@code 1} 表示当天有录像
     */
    public String monthFlags(String channelId, String month) {
        LocalDateTime period = TimeFormats.parse(month);
        if (period == null) {
            throw BizException.badRequest("月份格式应为 YYYYMM：" + month);
        }
        int days = period.toLocalDate().lengthOfMonth();
        StringBuilder flags = new StringBuilder("0".repeat(days));
        List<GbRecord> records = recordMapper.selectList(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId)
                .ge(GbRecord::getStartTime, period)
                .lt(GbRecord::getStartTime, period.plusMonths(1)));
        for (GbRecord r : records) {
            if (r.getStartTime() == null) {
                continue;
            }
            int day = r.getStartTime().getDayOfMonth();
            if (day >= 1 && day <= days) {
                flags.setCharAt(day - 1, '1');
            }
        }
        return flags.toString();
    }

    // ==================== 内部 ====================

    /**
     * 幂等写入录像记录。
     * 归档（recordId 来自 ZLM）与对账（随机 recordId）可能并发跑到同一文件，
     * 实测间隔仅 206 微秒，各自的「是否已入库」检查都通过，于是插入两条指向同一文件的记录。
     * 这里先按 {@code file_path} 查一次，数据库侧另有 {@code uk_gb_record_file_path} 唯一索引兜底。
     */
    private void saveRecord(GbRecord record) {
        GbRecord existing = recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, record.getRecordId()).last("limit 1"));
        if (existing == null && record.getFilePath() != null
                && !record.getFilePath().isBlank()) {
            // 同一文件已由另一条路径登记：更新它而不是再插一条
            existing = recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                    .eq(GbRecord::getFilePath, record.getFilePath()).last("limit 1"));
        }
        if (existing == null) {
            try {
                recordMapper.insert(record);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发窗口内对方先插入：唯一索引拦下，转为更新
                GbRecord race = recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                        .eq(GbRecord::getFilePath, record.getFilePath()).last("limit 1"));
                if (race == null) {
                    throw e;
                }
                record.setId(race.getId());
                recordMapper.updateById(record);
            }
        } else {
            record.setId(existing.getId());
            recordMapper.updateById(record);
        }
    }

    /** 把 ZLM 录像条目折算成库记录并落库（含存储类型与上传状态初始化）。 */
    private GbRecord persistRecording(Recording recording) {
        GbRecord record = toRecord(recording);
        record.setStorageType(storage.type());
        record.setUploadState(storage.enabled() ? "PENDING" : "NONE");
        saveRecord(record);
        return record;
    }

    /** 落库并（启用对象存储时）异步上传该录像文件。 */
    private GbRecord persistAndUpload(Recording recording) {
        GbRecord record = persistRecording(recording);
        if (storage.enabled()) {
            uploadAsync(record.getRecordId(), recording.getFilePath(), record.getChannelId());
        }
        return record;
    }

    private GbRecord toRecord(Recording recording) {
        if (recording == null) {
            return null;
        }
        GbRecord r = new GbRecord();
        r.setRecordId(recording.getRecordId());
        r.setChannelId(recording.getChannelId());
        // 同 toDiskRecord：device_id 是设备编码，需按通道反查（多通道设备下二者不同）
        r.setDeviceId(deviceIdOf(recording.getChannelId()));
        r.setName(recording.getChannelId());
        // 路径可能尚未确定：ZLM 自行生成文件名与日期子目录，
        // 需等首个分片落盘、on_mk_record_mp4 回调到达后才可得知
        String filePath = recording.getFilePath();
        r.setFilePath(filePath);
        r.setFileName(filePath == null ? null : new java.io.File(filePath).getName());

        // 【时间与大小一律以磁盘文件为准，不用内存条目的会话级时间】
        // 内存 Recording 的 startTime 曾是会话开始（bindFile 只绑首段），分片轮转后不再对应该文件，
        // 实测归档算出 duration 6501 秒而实际文件只有 1801 秒，且被按 file_path 更新进库。
        // 文件名里带真实开始时刻，lastModified 就是分片写完时刻 —— 与对账 toDiskRecord 同一套算法。
        LocalDateTime start = filePath == null ? null : parseZlmFileName(r.getFileName());
        LocalDateTime end = null;
        long size = recording.getFileSize();
        if (filePath != null) {
            java.io.File file = new java.io.File(filePath);
            if (file.exists()) {
                end = lastModified(file);
                size = file.length();
            }
        }
        if (start == null) {
            // 文件名解析不出（尚未落盘 / 命名不符合 ZLM 规则）：退回内存时间
            start = TimeFormats.ofEpochMilli(recording.getStartTime());
        }
        if (end == null) {
            end = recording.getEndTime() > 0
                    ? TimeFormats.ofEpochMilli(recording.getEndTime()) : null;
        }
        r.setStartTime(start);
        if (end != null) {
            if (end.isBefore(start)) {
                end = start;
            }
            r.setEndTime(end);
            r.setDuration((int) Duration.between(start, end).getSeconds());
        } else {
            r.setDuration((int) recording.durationSeconds());
        }
        r.setCloudRecord(true);
        r.setImportant(false);
        r.setCreateTime(LocalDateTime.now());
        r.setStorageType(ObjectStorageService.TYPE_LOCAL);
        r.setUploadState("NONE");
        r.setFileSize(size);
        return r;
    }

    /** 当前会话（供回放流信息查询）。 */
    public RtpSession session(String channelId) {
        return platform.sessionManager() == null ? null : platform.sessionManager().getByChannel(channelId);
    }
}
