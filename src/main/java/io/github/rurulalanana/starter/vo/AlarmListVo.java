package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 报警列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlarmListVo {

    /** 过滤后的报警总数。 */
    private Integer alarmCount;

    /** 报警保留天数。 */
    private Integer alarmReserveDays;

    /** 是否推送到 Redis。 */
    private Boolean alarmPublishToRedis;

    /** 报警列表。 */
    private List<AlarmVo> alarmList;
}
