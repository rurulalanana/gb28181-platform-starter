package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 转推列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelayListVo {

    /** 转推路数。 */
    private Integer relayCount;

    /** 转推列表。 */
    private List<RelayVo> relayList;
}
