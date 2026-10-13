package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 实时录像停止 / 查询的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordFileListVo {

    private String channelId;

    /** 是否仍在录像中。 */
    private Boolean recording;

    /** 录像文件数。 */
    private Integer recordCount;

    private List<CloudRecordVo> recordList;
}
