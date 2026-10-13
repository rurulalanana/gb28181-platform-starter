package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 直播流列表（过滤前总数 + 当前页）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamListVo {

    /** 过滤后的总条数。 */
    private Integer streamCount;

    /** 当前页直播流。 */
    private List<StreamVo> streams;
}
