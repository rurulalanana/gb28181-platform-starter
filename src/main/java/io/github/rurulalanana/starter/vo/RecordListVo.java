package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 设备侧录像列表（{@code playback/recordlist}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordListVo {

    private String deviceId;

    /** 录像段数量。 */
    private Integer sumNum;

    private List<RecordVo> recordList;
}
