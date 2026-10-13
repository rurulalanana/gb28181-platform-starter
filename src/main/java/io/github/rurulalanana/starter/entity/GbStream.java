package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 直播 / 回放流记录。
 *
 * <p>对应表 {@code gb_stream}。{@code streamKey} 为 ZLM 流名（{@code <channelId>/<序号>}），
 * 唯一标识一路流；同一通道的实时与回放流共用本表，以 {@code streamType} 区分。
 */
@Data
@TableName("gb_stream")
public class GbStream implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 流类型：实时直播。 */
    public static final String TYPE_LIVE = "live";
    /** 流类型：历史回放。 */
    public static final String TYPE_PLAYBACK = "playback";

    /** 状态：协商中（已发 INVITE，等首个 RTP 包）。 */
    public static final String STATE_NEGOTIATING = "NEGOTIATING";
    /** 状态：已收到 RTP 并解出轨道。 */
    public static final String STATE_ONLINE = "ONLINE";
    /** 状态：已关闭。 */
    public static final String STATE_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ZLM 流名（{@code <channelId>/<序号>}）。 */
    private String streamKey;

    private String channelId;
    private String deviceId;
    private String streamName;
    private String app;
    private String ssrc;
    private Integer rtpPort;

    /** {@link #TYPE_LIVE} / {@link #TYPE_PLAYBACK}。 */
    private String streamType;

    /** {@link #STATE_NEGOTIATING} / {@link #STATE_ONLINE} / {@link #STATE_CLOSED}。 */
    private String state;

    private String callId;

    private String httpFlv;
    private String wsFlv;
    private String hls;
    private String webrtc;
    private String rtsp;
    private String rtmp;

    /** 流传输模式：{@code UDP} / {@code TCP}。 */
    private String transport;

    private Boolean audio;
    private Boolean onDemand;
    private Boolean cloudRecord;

    /** 在线观看人数。 */
    private Integer viewers;

    private LocalDateTime startTime;
    private LocalDateTime updateTime;
}
