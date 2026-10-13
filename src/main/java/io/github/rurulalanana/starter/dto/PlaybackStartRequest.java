package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回放 / 下载开始参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PlaybackStartRequest extends DeviceRef {

    /** 开始时间，{@code YYYY-MM-DDTHH:mm:ss}。 */
    private String starttime;

    /** 结束时间，{@code YYYY-MM-DDTHH:mm:ss}；缺省为当前时间。 */
    private String endtime;

    /** 是否为下载模式（下载完成后可通过 playback/stop 拿到文件链接）。 */
    private Boolean download = false;

    /** 下载倍速，仅 {@code download=true} 时有效。 */
    private Integer downloadSpeed = 4;

    /** 设备侧文件路径（来自 {@code playback/recordlist} 的 FilePath）。 */
    private String filePath;

    /** 是否开启音频。 */
    private String audio = "config";

    /** 流传输模式。 */
    private String transport = "config";

    /** {@code transport=TCP} 时有效。 */
    private String transportMode = "passive";

    /** 码流编号（2022）。 */
    private Integer streamnumber;

    /** 拉流超时（秒）。 */
    private Integer timeout;
}
