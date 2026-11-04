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

    /**
     * WebRTC 实际下发给浏览器的 ICE 候选地址（ZLM {@code rtc.externIP}）。
     *
     * <p>与 {@link #mediaIp} 分开暴露：两者通常相同，但 WebRTC 走独立入口
     * （或本机调试要指向局域网 IP）时会被 {@code gb28181.rtc-extern-ip} 单独覆盖。
     * 排「协商成功但画面全黑」时，第一个要看的就是这个值是不是浏览器能到达的地址。
     */
    private String rtcExternIp;

    /** 说明。 */
    private String note;
}
