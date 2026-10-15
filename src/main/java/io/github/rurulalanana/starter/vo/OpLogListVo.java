package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 操作日志列表。 */
@Data
public class OpLogListVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总条数。 */
    private Long opLogCount;

    private List<OpLogVo> opLogList;
}
