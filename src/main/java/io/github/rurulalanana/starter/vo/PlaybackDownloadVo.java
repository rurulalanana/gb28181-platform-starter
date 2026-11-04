package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像下载相关的返回体。三个接口各用一个内部类：共用一个「大而全」的 VO 会出现一堆与当前场景
 * 无关的可空字段，很容易误读（比如把 start 返回的 totalSecond 当作已下载量）。
 */
public final class PlaybackDownloadVo {

    private PlaybackDownloadVo() {
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Start {
        /** 回放会话键，后续 progress / finish 都用它定位。 */
        private String streamId;
        /** 录像段总时长（秒）；0 表示未知。 */
        private Long totalSecond;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Progress {
        private String streamId;
        private Long recordedSecond;
        private Long totalSecond;
        /** 百分比 0~100。 */
        private Integer percent;
        private Boolean finished;
        /** 已产出文件大小（字节）；未落盘为 0。 */
        private Long fileSize;
    }

    /**
     * 一个后台下载任务。比 {@link Progress} 多出「哪个通道、哪一段、倍速、结果地址」：
     * 任务列表是跨页面、跨通道的，光有进度无法区分是哪一路。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Task {
        private String taskId;
        private String deviceId;
        private String channelId;
        /** 通道名（取不到时为通道编码）。 */
        private String channelName;
        private String startAt;
        private String endAt;
        private Long recordedSecond;
        private Long totalSecond;
        private Integer percent;
        private Integer speed;
        private Boolean finished;
        private Boolean ok;
        private String fileName;
        private Long fileSize;
        /** 带签名的下载地址；未完成时为空。 */
        private String url;
        /** 失败/取消原因。 */
        private String message;
        private Long elapsedSecond;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Finish {
        private Boolean ok;
        private String message;
        /** 磁盘绝对路径（排查用，正常下载不用它）。 */
        private String filePath;
        private String fileName;
        private Long fileSize;
        /** 带签名的下载地址，可直接交给浏览器。 */
        private String url;
    }
}
