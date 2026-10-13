package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单条流量记录。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamLogItemVo {

    private String deviceId;
    private String channelId;
    private Long bytes;
    private String time;
}
