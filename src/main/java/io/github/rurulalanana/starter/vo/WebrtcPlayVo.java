package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** WebRTC SDP 交换应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebrtcPlayVo {

    /** 本次协商的 peer id。 */
    private String peerId;

    /** 媒体服务返回的 SDP answer。 */
    private String sdp;

    /** 流 ID（{@code app/stream}）。 */
    private String streamId;

    /** WebRTC 播放地址。 */
    private String webrtc;
}
