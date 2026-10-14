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

    /** 存储类型：{@code LOCAL} / {@code OSS}。 */
    private String storageType;

    /** 上传状态：{@code NONE} / {@code PENDING} / {@code UPLOADED} / {@code FAILED}。 */
    private String uploadState;

    /** 是否正在录制（录制中不可下载）。 */
    private Boolean recording;
}
