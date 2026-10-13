package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 单条位置记录。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PositionLogVo {

    private String deviceId;
    private String channelId;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private BigDecimal speed;
    private BigDecimal direction;
    private String time;
}
