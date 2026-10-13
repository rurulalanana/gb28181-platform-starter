package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：录像点播结果（{@code POST /api/recordings/{id}/play}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeRecordingPlayVo {

    private Boolean ok;
    private String fileUrl;
    private NativePlayUrlsVo urls;
    private String message;
}
