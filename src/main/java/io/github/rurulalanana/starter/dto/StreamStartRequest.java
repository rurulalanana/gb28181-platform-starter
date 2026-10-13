package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 直播开始参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StreamStartRequest extends DeviceRef {

    /** 转推 CDN 地址，形如 {@code rtmp://xxx}。 */
    private String cdn;

    /** 是否开启音频：{@code true}/{@code false}/{@code config}（读通道配置）。 */
    private String audio = "config";

    /** 流传输模式：{@code TCP}/{@code UDP}/{@code config}。 */
    private String transport = "config";

    /** {@code transport=TCP} 时有效：{@code passive} 被动 / {@code active} 主动。 */
    private String transportMode = "passive";

    /** 码流编号（2022）：0 主码流 / 1 子码流。 */
    private Integer streamnumber;

    /** 拉流前是否检查通道在线状态。 */
    private Boolean checkChannelStatus = false;

    /** 拉流超时（秒）。 */
    private Integer timeout;
}
