package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 巡航组查询结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelNavigateVo {

    private String deviceId;

    /** 巡航组数量。 */
    private Integer sumNum;

    /** 巡航组列表。 */
    private List<CruiseGroupVo> groupList;

    /** 说明。 */
    private String note;
}
