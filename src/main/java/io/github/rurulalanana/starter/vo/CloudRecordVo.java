package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像段（云端录像按日查询 / 区间播放）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudRecordVo {

    /** 录像 ID（点播 / 停止点播用）。 */
    private String recordId;

    /** 通道编码。 */
    private String channelId;

    private String name;

    /** 开始时间（{@code yyyyMMddHHmmss}）。 */
    private String startAt;

    /** 结束时间（{@code yyyyMMddHHmmss}）；录制中为空。 */
    private String endTime;

    private Long fileSize;

    /** 录像时长（秒）。 */
    private Integer duration;

    /** 播放地址。 */
    private String hls;

    /** 是否重要录像。 */
    private Boolean important;

    /** 下载地址（对象存储为签名/外链，本地为平台接口）。 */
    private String downloadUrl;

    /**
     * 直接播放地址（同源相对路径，如 {@code /record/xxx/....mp4}）。
     *
     * <p>前端拿它按需 Range 下载并做浏览器端重封装。
     * 文件实体由 nginx 直读磁盘提供（见 {@code deploy/nginx-record.conf}）；
     * 无 nginx 的部署可开 {@code gb28181.record-static-enabled} 由本进程提供。
     * 对象存储（OSS）录像该字段为空，走 {@code downloadUrl}。
     */
    private String playUrl;

    /** 存储类型：{@code LOCAL} / {@code OSS}。 */
    private String storageType;

    /** 上传状态：{@code NONE} / {@code PENDING} / {@code UPLOADED} / {@code FAILED}。 */
    private String uploadState;

    /** 是否正在录制（录制中不可下载）。 */
    private Boolean recording;
}
