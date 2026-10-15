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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    private final Gb28181MediaPlatform platform;
    private final GbRecordMapper recordMapper;
    private final GbDeviceRecordMapper deviceRecordMapper;
    private final ObjectStorageService storage;
    private final io.github.rurulalanana.starter.storage.ObjectStorageProperties storageProps;
    private final StreamService streamService;

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

    /** 云端录像是否正在录制。 */
    public boolean isRecording(String channelId) {
        return platform.recordingService() != null
                && platform.recordingService().isRecording(channelId);
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

    private void saveRecord(GbRecord record) {
        GbRecord existing = recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, record.getRecordId()).last("limit 1"));
        if (existing == null) {
            recordMapper.insert(record);
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
        r.setDeviceId(recording.getChannelId());
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
