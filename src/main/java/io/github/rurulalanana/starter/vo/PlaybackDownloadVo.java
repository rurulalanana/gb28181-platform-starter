package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 录像下载相关的返回体。
 *
 * <p>三个接口各用一个内部类而不共用一个「大而全」的 VO：
 * 接口契约里出现一堆与当前场景无关的可空字段，前端很容易误读
 * （比如把 start 返回的 totalSecond 当作已下载量）。
 */
public final class PlaybackDownloadVo {

    private PlaybackDownloadVo() {
    }

    /** 开始下载的返回。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Start {
        /** 回放会话键，后续 progress / finish 都用它定位。 */
        private String streamId;
        /** 录像段总时长（秒）；0 表示未知。 */
        private Long totalSecond;
    }

    /** 下载进度。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Progress {
        /** 已录制秒数。 */
        private Long recordedSecond;
        /** 录像段总时长（秒）。 */
        private Long totalSecond;
        /** 百分比 0~100。 */
        private Integer percent;
        /** 是否已录完。 */
        private Boolean finished;
        /** 已产出文件大小（字节）；未落盘为 0。 */
        private Long fileSize;
    }

    /** 下载完成的返回。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Finish {
        private Boolean ok;
        /** 失败原因（{@code ok=false} 时）。 */
        private String message;
        /** 磁盘绝对路径（排查用，正常下载不用它）。 */
        private String filePath;
        /** 建议的下载文件名。 */
        private String fileName;
        private Long fileSize;
        /** 带签名的下载地址，可直接交给浏览器。 */
        private String url;
    }
}
