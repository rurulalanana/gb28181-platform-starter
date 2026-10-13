package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：设备录像段（{@code GET /api/records/...}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeRecordVo {

    private String deviceId;
    private String name;
    private String filePath;
    private String type;
    private String startTime;
    private String endTime;
    private Long fileSize;
}
