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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 录像服务：设备侧录像检索（标准 9.8）与平台侧云端录像管理。
 *
 * <p>两套录像语义不同，务必区分：
 * <ul>
 *   <li><b>设备录像</b>：文件在设备（NVR / SD 卡）上，平台通过 {@code RecordInfo}
 *       查询目录，通过回放 INVITE 让设备把流推回来。</li>
 *   <li><b>云端录像</b>：平台用 ZLMediaKit 把收到的实时流录成 MP4，文件在平台磁盘上；
 *       启用对象存储（{@code gb28181.oss.enabled=true}）时停止录制后自动上传。</li>
 * </ul>
 *
 * <p><b>云端录像元数据以 {@code gb_record} 表为准</b>：ZLMediaKit 的内存索引只用于
 * 查询「正在录制中」的条目，历史条目一律从库读取，避免重启后列表丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    /** 设备录像检索默认超时（秒）。 */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    /** 等待流建立的总超时（毫秒）：设备推流通常 1~3 秒内开始。 */
    private static final long STREAM_READY_TIMEOUT_MS = 12_000;

    /** 等待流建立的轮询间隔（毫秒）。 */
    private static final long STREAM_READY_POLL_MS = 300;

    /** 归档时等待 ZLM 写终态的时长（毫秒）：录制停止是异步的，需给回调留时间。 */
    private static final long ARCHIVE_WAIT_MS = 3000;

    /** 归档等待终态的轮询间隔（毫秒）。 */
    private static final long ARCHIVE_WAIT_POLL_MS = 100;

    private final Gb28181MediaPlatform platform;
    private final GbRecordMapper recordMapper;
    private final GbDeviceRecordMapper deviceRecordMapper;
    private final io.github.rurulalanana.starter.mapper.GbChannelMapper channelMapper;
    private final ObjectStorageService storage;
    private final io.github.rurulalanana.starter.storage.ObjectStorageProperties storageProps;
    private final StreamService streamService;
    private final Gb28181Properties props;

    /**
     * 已删除录像的「墓碑」：文件路径 → 删除时刻。
     *
     * <p><b>为什么需要</b>：{@link #reconcileAutoRecordings} 是按「磁盘上有文件、
     * 库里没有记录」来补登记的。而删除接口是「先删文件、再删记录」—— 文件删除
     * 可能失败（权限、文件被占用、OSS 本地副本策略），此时库里记录已删、
     * 磁盘文件还在，下一次对账就会把刚删掉的录像<b>重新登记回来</b>，
     * 表现为「删除后刷新又出现了」。
     *
     * <p>用墓碑记住「这个路径是用户主动删的」，对账时跳过。带过期时间，
     * 避免无限增长：文件最终被清理后墓碑也没有存在意义。
     *
     * <p><b>内存实现</b>：重启后墓碑丢失，但对账也只在启动 30 秒后才跑，
     * 且文件通常已被删掉；重启后仍残留的删除失败文件被重新登记属于可接受的
     * 降级（用户可再删一次）。用 DB 存墓碑需要建表，收益不成比例。
     */
    private final Map<String, Long> deletedPaths = new ConcurrentHashMap<>();

    /** 墓碑保留时长（毫秒）：超过后允许对账重新登记（文件若仍在）。 */
    private static final long TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1000L;

    // ==================== 设备侧录像（RecordInfo） ====================

    /**
     * 检索设备录像（标准 9.8），同步等待应答。
     *
     * @param channelId 通道编码
     * @param startTime ISO 格式开始时间
     * @param endTime   ISO 格式结束时间
     * @param timeoutSeconds 等待设备应答的超时
     * @return 录像段列表；设备不应答时返回空列表
     */
    public List<RecordItem> queryDeviceRecords(String channelId, String startTime,
                                               String endTime, Integer timeoutSeconds) {
        Gb28181MediaService media = platform.mediaService();
        if (media == null) {
            throw BizException.badRequest("媒体服务未启动");
        }
        CompletableFuture<List<RecordItem>> future = new CompletableFuture<>();
        media.queryRecordInfo(channelId, startTime, endTime, future::complete);
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? QUERY_TIMEOUT_SECONDS : timeoutSeconds;
        try {
            List<RecordItem> items = future.get(timeout, TimeUnit.SECONDS);
            cacheDeviceRecords(channelId, items);
            return items;
        } catch (Exception e) {
            log.warn("设备录像检索超时或失败: channel={} {}", channelId, e.getMessage());
            return List.of();
        }
    }

    /** 把检索结果写入本地缓存表，便于分页查询与按天统计。 */
    @Transactional
    public void cacheDeviceRecords(String channelId, List<RecordItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (RecordItem item : items) {
            if (item.getFilePath() == null) {
                continue;
            }
            GbDeviceRecord existing = deviceRecordMapper.selectOne(
                    Wrappers.<GbDeviceRecord>lambdaQuery()
                            .eq(GbDeviceRecord::getChannelId, channelId)
                            .eq(GbDeviceRecord::getFilePath, item.getFilePath())
                            .last("limit 1"));
            GbDeviceRecord record = existing == null ? new GbDeviceRecord() : existing;
            record.setChannelId(channelId);
            record.setDeviceId(item.getDeviceId());
            record.setName(item.getName());
            record.setFilePath(item.getFilePath());
            record.setAddress(item.getAddress());
            record.setStartTime(TimeFormats.of(item.getStartTime()));
            record.setEndTime(TimeFormats.of(item.getEndTime()));
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

    /** 本地缓存的设备录像查询。 */
    public List<GbDeviceRecord> deviceRecords(String channelId, LocalDateTime from, LocalDateTime to,
                                              String type) {
        return deviceRecordMapper.selectList(Wrappers.<GbDeviceRecord>lambdaQuery()
                .eq(channelId != null && !channelId.isBlank(), GbDeviceRecord::getChannelId, channelId)
                .eq(type != null && !type.isBlank() && !"all".equalsIgnoreCase(type),
                        GbDeviceRecord::getType, type)
                .ge(from != null, GbDeviceRecord::getStartTime, from)
                .le(to != null, GbDeviceRecord::getStartTime, to)
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
     *
     * <p><b>录制要求流已建立</b>：ZLM 的 {@code mk_recorder_start} 按
     * {@code app/stream} 查找媒体源，源不存在时直接失败
     * （{@code 未找到相关的MediaSource}）。因此这里在流未建立时先自动点播，
     * 并轮询等待 ZLM 真正收到 RTP 包后再开录 —— 否则用户点「开始录像」
     * 只会得到一个失败提示。
     *
     * @param deviceId  设备编码（用于自动点播）
     * @param channelId 通道编码
     */
    @Transactional
    public GbRecord startCloudRecord(String deviceId, String channelId) {
        if (platform.recordingService() == null) {
            throw BizException.badRequest("录像服务未启动");
        }
        ensureStreamReady(deviceId, channelId);
        Recording recording = platform.recordingService().startRecording(channelId);
        GbRecord record = toRecord(recording);
        record.setStorageType(storage.type());
        record.setUploadState(storage.enabled() ? "PENDING" : "NONE");
        saveRecord(record);
        return record;
    }

    /**
     * 直接开始录像，<b>不点播、不等流</b>。
     *
     * <p>专供流上线回调使用：调用时机就是流刚就绪，再走
     * {@link #startCloudRecord} 的点播+轮询是多余的（还可能重复 INVITE）。
     *
     * <p>落库由归档路径统一处理：自动录像在流下线时由
     * {@link #archiveRecording} 落库；若中途被手动停止，则走
     * {@link #stopCloudRecord} 的常规落库。
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
     *
     * <p>用「ZLM 是否收到过 RTP 包」而非「会话是否存在」作为就绪判据：
     * 会话在 INVITE 发出后立刻就有，但设备要过几百毫秒到数秒才开始推流，
     * 这期间媒体源尚未注册，录制仍会失败。
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
     * 停止平台侧录像并归档。
     *
     * <p>启用对象存储时，录制结束后<b>异步</b>上传 MP4（大文件不阻塞接口），
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
        GbRecord record = toRecord(recording);
        record.setStorageType(storage.type());
        record.setUploadState(storage.enabled() ? "PENDING" : "NONE");
        saveRecord(record);
        if (storage.enabled()) {
            uploadAsync(record.getRecordId(), recording.getFilePath(), record.getChannelId());
        }
        return record;
    }

    /**
     * 异步上传录像到对象存储。
     *
     * <p>上传成功后按配置决定是否删除本地文件；失败只记日志并置
     * {@code upload_state=FAILED}，本地文件保留，不影响录像的本地下载。
     *
     * <p><b>事务安全</b>：调用方在事务内时，上传任务必须等事务提交后再启动，
     * 否则异步线程可能读不到刚写入的 {@code gb_record} 记录（另一个连接看不到未提交数据）。
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

    /** 回写上传结果。 */
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
     *
     * <p><b>为什么扫磁盘而不是读 ZLM 内存索引</b>：实测流下线时 ZLM 侧的
     * 停录与索引簿记存在竞态 —— 条目可能先被摘除，导致
     * {@code stopRecording} 拿不到条目、终态永不归档（文件留在磁盘上、
     * 云端录像列表却看不到）。而磁盘文件是最终事实：ZLM 完成归档后
     * 会把临时名改成正式名（无 {@code .} 前缀），路径结构固定为
     * {@code <recordRoot>/<channelId>/record/<app>/<zlmStream>/<yyyy-MM-dd>/*-<idx>.mp4}。
     *
     * <p>目录第三层（{@code record}）以下是 ZLM 的固定布局；
     * 通道编码取自第一层目录名。
     *
     * @return 本次补登记的文件数
     */
    public int reconcileAutoRecordings() {
        String root = props.getRecordRoot();
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir") + "/record";
        }
        File rootDir = new File(root);
        if (!rootDir.isDirectory()) {
            return 0;
        }
        int count = 0;
        File[] channelDirs = rootDir.listFiles(File::isDirectory);
        if (channelDirs == null) {
            return 0;
        }
        for (File channelDir : channelDirs) {
            String channelId = channelDir.getName();
            List<File> files = new ArrayList<>();
            collectMp4s(channelDir, files);
            for (File f : files) {
                // 正在写的分片是临时名（ZLM 用 . 前缀，关闭后改名）：跳过
                if (f.getName().startsWith(".")) {
                    continue;
                }
                String absPath = f.getAbsolutePath();
                // 用户主动删过的路径不得复活（删除时文件可能没删掉）
                if (isDeleted(absPath)) {
                    continue;
                }
                Long exists = recordMapper.selectCount(Wrappers.<GbRecord>lambdaQuery()
                        .eq(GbRecord::getFilePath, absPath));
                if (exists != null && exists > 0) {
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

    /** 递归收集目录下的 mp4 文件。 */
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

    /** 从磁盘文件构建录像条目（时间从文件名/修改时间推断，文件名格式见 ZLM Mp4.cpp）。 */
    private GbRecord toDiskRecord(String channelId, File f) {
        GbRecord r = new GbRecord();
        r.setRecordId(java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16));
        r.setChannelId(channelId);
        // device_id 必须是「设备编码」而不是通道编码：对账只有目录名（= 通道编码），
        // 需按 gb_channel 反查真实设备。查不到时退回通道编码（设备直连场景二者相同），
        // 至少保证非空——否则 gb_record.device_id 为 null，按设备过滤的查询会漏掉它。
        r.setDeviceId(deviceIdOf(channelId));
        r.setName(channelId);
        r.setFilePath(f.getAbsolutePath());
        r.setFileName(f.getName());
        LocalDateTime end = lastModified(f);
        // 文件名形如 2026-10-16-10-36-18-0.mp4，前 19 字符即开始时间；
        // 文件名解析失败时用修改时间兜底（此时时长为 0，可接受）
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
     * 按通道编码反查设备编码。
     *
     * <p>对账只有目录名（= 通道编码），而 {@code gb_record.device_id} 语义是
     * 设备编码。两者在多通道设备下不同（如通道 {@code 34020000001320000001}
     * 属于设备 {@code 34020000002000000006}），直接写通道编码会让
     * 「按设备查录像」漏掉这些记录。
     *
     * <p>查不到时退回通道编码：设备直连场景二者相同，且保证字段非空。
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

    /**
     * 云端录像是否正在录制。
     */
    public boolean isRecording(String channelId) {
        return platform.recordingService() != null
                && platform.recordingService().isRecording(channelId);
    }

    /**
     * 归档一条已结束的录像（自动录像的收尾）。
     *
     * <p><b>两条数据来源，索引优先、磁盘兜底</b>：
     * <ol>
     *   <li>ZLM 内存索引里「已结束」（{@code endTime > 0}）的条目 —— 元数据最全；</li>
     *   <li>索引里没有时，<b>扫描该通道的磁盘目录</b>补登记。</li>
     * </ol>
     * 必须有兜底：实测停止录像时 ZLM 索引里的条目 {@code endTime} <b>长期为 0</b>
     * （连续轮询 31 次不变），而文件早已落盘并可播 —— 只认索引会让归档永远拿不到
     * 东西，录像要等 60 秒对账才出现（这正是线上现象）。磁盘扫描是已被
     * {@link #reconcileAutoRecordings} 验证可靠的路径。
     *
     * <p><b>为什么不用 {@link #stopCloudRecord}</b>：那是对外接口（record/stop），
     * 它会再调一次 {@code mk_recorder_stop} —— 而流下线时 ZLM 已自动停录，
     * 再调一次只会拿到 null。这里只做「取条目 → 落库 → 异步上传」。
     *
     * @return 归档成功返回库里的条目；无可归档内容返回 null
     */
    public GbRecord archiveRecording(String channelId) {
        return archiveRecording(channelId, 0L);
    }

    /**
     * 归档一条已结束的录像，只认「不早于 {@code sinceMillis} 开始」的条目。
     *
     * <p><b>为什么必须限定时间窗口</b>：ZLM 的录像索引是<b>累积</b>的，
     * 已结束的历史条目会一直留着。若不加限制，任意一次归档都会命中
     * 「第一个已结束条目」——实测回放会话关闭时就拿上一次直播的录像
     * 重复入库（日志里同一个 recordId 归档两次）。
     *
     * @param sinceMillis 只归档开始时间不早于此时刻的条目（毫秒）；
     *                    {@code 0} 表示不限（对账/手动归档场景）
     * @return 归档成功返回库里的条目；无可归档内容返回 null
     */
    public GbRecord archiveRecording(String channelId, long sinceMillis) {
        if (platform.recordingService() == null) {
            return null;
        }
        // 路径一：ZLM 内存索引的终态条目
        Recording recording = awaitTerminalRecording(channelId, sinceMillis);
        if (recording != null) {
            GbRecord record = toRecord(recording);
            record.setStorageType(storage.type());
            record.setUploadState(storage.enabled() ? "PENDING" : "NONE");
            saveRecord(record);
            if (storage.enabled()) {
                uploadAsync(record.getRecordId(), recording.getFilePath(), record.getChannelId());
            }
            return record;
        }
        // 路径二：索引不可用，扫该通道目录补登记（取最新一个未入库的文件）
        return archiveFromDisk(channelId, sinceMillis);
    }

    /**
     * 从磁盘补登记该通道最新一个尚未入库的录像文件。
     *
     * <p>与 {@link #reconcileAutoRecordings} 同一套判据（跳过 {@code .} 前缀的
     * 写入中分片、跳过墓碑、跳过已入库），但只处理<b>指定通道</b>、
     * 且只登记<b>最新一个</b>文件 —— 归档场景只关心刚结束的那段录像。
     *
     * @param sinceMillis 只登记修改时间不早于此时刻的文件（毫秒）；0 表示不限
     */
    private GbRecord archiveFromDisk(String channelId, long sinceMillis) {
        String root = props.getRecordRoot();
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir") + "/record";
        }
        File channelDir = new File(root, channelId);
        if (!channelDir.isDirectory()) {
            return null;
        }
        List<File> files = new ArrayList<>();
        collectMp4s(channelDir, files);
        // 按修改时间倒序：最新结束的那段就是本次要归档的
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (f.getName().startsWith(".")) {
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
     *
     * <p><b>为什么需要等待</b>：录制停止是 ZLM 回调线程异步做的
     * （{@code RecordingService.onStreamOffline → stopRecording} 里才写 {@code endTime}），
     * 而归档在「停止直播」接口的线程上同步执行，两者存在竞态 ——
     * 归档若跑在前面，索引里的 {@code endTime} 仍是 0，会被过滤掉，
     * 于是本次归档静默失败、录像要等 60 秒对账才出现（实测现象）。
     *
     * <p>等待是有界的：一旦拿到终态条目立即返回；索引里压根没有该通道的条目
     * （未开启录像）时立刻返回 null，不做无谓等待。
     *
     * @param sinceMillis 只认开始时间不早于此时刻的条目（毫秒）；0 表示不限
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
     * 云端录像列表。
     *
     * <p>数据源是 {@code gb_record} 表（历史条目重启不丢），
     * 再与 ZLMediaKit 内存索引合并：内存中「录制中」的条目用实时状态覆盖，
     * 库中缺失的内存条目也会补上（例如录像刚开始、库尚未落盘的瞬间）。
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
            java.util.Map<String, GbRecord> byId = new java.util.LinkedHashMap<>();
            for (GbRecord r : out) {
                byId.put(r.getRecordId(), r);
            }
            for (Recording r : platform.recordingService().search(channelId,
                    from == null ? null : TimeFormats.toDate(from).getTime(),
                    to == null ? null : TimeFormats.toDate(to).getTime())) {
                GbRecord live = toRecord(r);
                GbRecord existing = byId.get(live.getRecordId());
                if (existing == null) {
                    out.add(live);
                } else {
                    // 录制中的条目以内存为准（结束时间/时长实时变化）
                    if (existing.getEndTime() == null) {
                        existing.setEndTime(live.getEndTime());
                        existing.setDuration(live.getDuration());
                        existing.setFileSize(live.getFileSize());
                    }
                }
            }
            out.sort(java.util.Comparator.comparing(GbRecord::getStartTime,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        }
        return out;
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
        if (ObjectStorageService.TYPE_OSS.equals(record.getStorageType())
                && record.getObjectKey() != null && !record.getObjectKey().isBlank()) {
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
        if (ObjectStorageService.TYPE_OSS.equals(record.getStorageType())
                && record.getObjectKey() != null && !record.getObjectKey().isBlank()) {
            return true;
        }
        return record.getFilePath() != null && Files.exists(Path.of(record.getFilePath()));
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
        if (ObjectStorageService.TYPE_OSS.equals(record.getStorageType())
                && record.getObjectKey() != null && !record.getObjectKey().isBlank()) {
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
     * 删除云端录像记录（不删文件）。
     *
     * <p>同时清理 ZLM 内存索引：{@code cloudRecords} 会合并索引里的条目，
     * 只删库不删索引会让录像在列表里「删不掉」（刷新后依然出现）。
     */
    public boolean removeRecord(String recordId) {
        if (platform.recordingService() != null) {
            platform.recordingService().forget(recordId);
        }
        return recordMapper.delete(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, recordId)) > 0;
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
     *
     * <p><b>必须按秒区间匹配</b>：{@code period} 只精确到秒
     * （{@code YYYYMMDDHHmmss}），而库里的 {@code start_time} 带毫秒
     * （如 {@code 14:40:10.113}）。若直接 {@code eq(start_time, 14:40:10.000)}
     * 永远匹配不上，{@code remove} / {@code setimportant} 会一律报「录像不存在」。
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

    /** 删除某通道的全部云端录像记录。 */
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
     *
     * <p><b>为什么按 {@code file_path} 也要去重</b>：同一个录像文件有两条
     * 写入路径 —— 归档（{@code archiveRecording}，recordId 来自 ZLM 索引）
     * 与对账（{@code reconcileAutoRecordings}，recordId 随机生成）。
     * 两者可能<b>并发</b>跑到同一文件：实测间隔仅 206 微秒，各自的
     * 「是否已入库」检查都通过了，于是插入两条指向同一文件的记录。
     * 数据库侧已加 {@code uk_gb_record_file_path} 唯一索引兜底，
     * 这里先按 {@code file_path} 查一次，避免把异常当正常路径走。
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
        r.setStartTime(TimeFormats.ofEpochMilli(recording.getStartTime()));
        if (recording.getEndTime() > 0) {
            r.setEndTime(TimeFormats.ofEpochMilli(recording.getEndTime()));
        }
        r.setDuration((int) recording.durationSeconds());
        r.setCloudRecord(true);
        r.setImportant(false);
        r.setCreateTime(LocalDateTime.now());
        r.setStorageType(ObjectStorageService.TYPE_LOCAL);
        r.setUploadState("NONE");
        // 优先用回调上报的大小；缺失时回退磁盘实际大小
        long size = recording.getFileSize();
        if (size <= 0 && filePath != null) {
            java.io.File file = new java.io.File(filePath);
            size = file.exists() ? file.length() : 0L;
        }
        r.setFileSize(size);
        return r;
    }

    /** 当前会话（供回放流信息查询）。 */
    public RtpSession session(String channelId) {
        return platform.sessionManager() == null ? null : platform.sessionManager().getByChannel(channelId);
    }
}
