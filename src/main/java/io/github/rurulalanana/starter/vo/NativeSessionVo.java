package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：媒体会话信息（{@code POST /api/play/...} 等）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeSessionVo {

    /** 是否成功。 */
    private Boolean ok;

    /** 无流时 {@code false}。 */
    private Boolean streaming;

    private String channelId;
    private String deviceId;

    /** 会话类型。 */
    private String type;

    private String callId;
    private Integer port;
    private String ssrc;

    /** 会话状态。 */
    private String state;

    /** 媒体 URI（回放 / 下载时为文件路径）。 */
    private String mediaUri;

    /** 播放地址。 */
    private NativePlayUrlsVo urls;

    /** 附加说明（如「录像不存在」）。 */
    private String message;
}
