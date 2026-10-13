package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 流量统计。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamLogVo {

    /** 流量记录列表。 */
    private java.util.List<StreamLogItemVo> list;

    /** 说明。 */
    private String note;
}
