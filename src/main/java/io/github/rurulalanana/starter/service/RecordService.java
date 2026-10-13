package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaService;
import io.github.rurulalanana.gb28181.sip.zlm.PlayUrls;
import io.github.rurulalanana.gb28181.sip.zlm.RecordItem;
import io.github.rurulalanana.gb28181.sip.zlm.Recording;
import io.github.rurulalanana.gb28181.sip.zlm.RtpSession;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.TimeFormats;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import io.github.rurulalanana.starter.entity.GbRecord;
import io.github.rurulalanana.starter.mapper.GbDeviceRecordMapper;
import io.github.rurulalanana.starter.mapper.GbRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li><b>云端录像</b>：平台用 ZLMediaKit 把收到的实时流录成 MP4，文件在平台磁盘上。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    /** 设备录像检索默认超时（秒）。 */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final Gb28181MediaPlatform platform;
    private final GbRecordMapper recordMapper;
    private final GbDeviceRecordMapper deviceRecordMapper;

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

    /** 开始平台侧录像。 */
    @Transactional
    public GbRecord startCloudRecord(String channelId) {
        if (platform.recordingService() == null) {
            throw BizException.badRequest("录像服务未启动");
        }
        Recording recording = platform.recordingService().startRecording(channelId);
        GbRecord record = toRecord(recording);
        saveRecord(record);
        return record;
    }

    /** 停止平台侧录像并归档。 */
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
        saveRecord(record);
        return record;
    }

    /** 云端录像是否正在录制。 */
    public boolean isRecording(String channelId) {
        return platform.recordingService() != null
                && platform.recordingService().isRecording(channelId);
    }

    /** 云端录像列表（内存索引，正在录制的也会出现）。 */
    public List<GbRecord> cloudRecords(String channelId, LocalDateTime from, LocalDateTime to) {
        List<GbRecord> out = new ArrayList<>();
        if (platform.recordingService() != null) {
            for (Recording r : platform.recordingService().search(channelId,
                    from == null ? null : TimeFormats.toDate(from).getTime(),
                    to == null ? null : TimeFormats.toDate(to).getTime())) {
                out.add(toRecord(r));
            }
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
            throw BizException.notFound("录像不存在：" + recordId);
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

    /** 按录像 ID 查云端录像。 */
    public GbRecord findRecord(String recordId) {
        return recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getRecordId, recordId).last("limit 1"));
    }

    /** 删除云端录像记录（不删文件）。 */
    public boolean removeRecord(String recordId) {
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

    /** 按录像开始时间定位（GBS 的 {@code period} 参数即开始时间）。 */
    public GbRecord findByPeriod(String channelId, String period) {
        LocalDateTime start = TimeFormats.parse(period);
        if (start == null) {
            throw BizException.badRequest("时间格式应为 YYYYMMDDHHmmss：" + period);
        }
        return recordMapper.selectOne(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId)
                .eq(GbRecord::getStartTime, start)
                .last("limit 1"));
    }

    /** 删除某通道的全部云端录像记录。 */
    public int removeChannelRecords(String channelId) {
        return recordMapper.delete(Wrappers.<GbRecord>lambdaQuery()
                .eq(GbRecord::getChannelId, channelId));
    }

    /** 删除某通道某天的云端录像记录。 */
    public int removeDailyRecords(String channelId, String yyyymmdd) {
        LocalDateTime day = TimeFormats.parse(yyyymmdd);
        if (day == null) {
            throw BizException.badRequest("日期格式应为 YYYYMMDD：" + yyyymmdd);
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
        r.setFilePath(recording.getFilePath());
        r.setFileName(new java.io.File(recording.getFilePath()).getName());
        r.setStartTime(TimeFormats.ofEpochMilli(recording.getStartTime()));
        if (recording.getEndTime() > 0) {
            r.setEndTime(TimeFormats.ofEpochMilli(recording.getEndTime()));
        }
        r.setDuration((int) recording.durationSeconds());
        r.setCloudRecord(true);
        r.setImportant(false);
        r.setCreateTime(LocalDateTime.now());
        java.io.File file = new java.io.File(recording.getFilePath());
        r.setFileSize(file.exists() ? file.length() : 0L);
        return r;
    }

    /** 当前会话（供回放流信息查询）。 */
    public RtpSession session(String channelId) {
        return platform.sessionManager() == null ? null : platform.sessionManager().getByChannel(channelId);
    }
}
