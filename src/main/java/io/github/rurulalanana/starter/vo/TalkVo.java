package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 语音喊话应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TalkVo {

    /** 固定 true，表示广播通知已下发。 */
    private Boolean ok;

    private String channelId;

    /** 音频格式。 */
    private String format;

    /** 说明。 */
    private String note;
}
