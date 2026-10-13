package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设备 / 通道在线统计。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnlineStatsVo {

    private Long deviceTotal;
    private Long deviceOnline;
    private Long channelTotal;
    private Long channelOnline;
}
