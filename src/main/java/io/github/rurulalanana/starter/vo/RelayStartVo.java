package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 启动转推的结果：新转推的流 ID + 该通道当前全部转推。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelayStartVo {

    /** 源流 ID。 */
    private String streamId;

    /** 该通道当前转推路数。 */
    private Integer relayCount;

    /** 该通道当前转推列表。 */
    private List<RelayVo> relayList;
}
