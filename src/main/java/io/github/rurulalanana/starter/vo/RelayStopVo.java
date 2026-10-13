package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 停止转推的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelayStopVo {

    /** 本次移除的路数。 */
    private Integer removed;

    /** 剩余转推路数。 */
    private Integer relayCount;

    /** 剩余转推列表。 */
    private List<RelayVo> relayList;
}
