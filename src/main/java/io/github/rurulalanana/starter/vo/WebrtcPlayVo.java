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

    /**
     * 该路流视频轨的实际编码（{@code H264} / {@code H265}），取不到时为 null。
     *
     * <p>ZLM 不转码，answer 里的编码就是流本身的编码。浏览器能不能解直接决定
     * 有没有画面：H265 只有 Chrome ≥136（需硬件 HEVC 解码）与 Safari 支持。
     * 把它回给调用方，出问题时不必再抓 SDP 就能判断「是编码不支持还是网络不通」。
     */
    private String videoCodec;

    /** WebRTC 播放地址。 */
    private String webrtc;
}
