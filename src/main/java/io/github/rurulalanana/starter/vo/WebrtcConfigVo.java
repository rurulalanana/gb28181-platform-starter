package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** WebRTC 播放参数。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebrtcConfigVo {

    /** 媒体服务 WebRTC 端口。 */
    private Integer webRtcPort;

    /** ZLM 应用名。 */
    private String app;

    /** 媒体服务对外 IP。 */
    private String mediaIp;

    /** 说明。 */
    private String note;
}
