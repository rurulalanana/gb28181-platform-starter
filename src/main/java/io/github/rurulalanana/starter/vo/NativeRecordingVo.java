package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：平台侧录像（{@code GET /api/recordings}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeRecordingVo {

    private String recordId;
    private String channelId;
    private String status;
    private Long startTime;
    private Long endTime;
    private Long durationSeconds;
    private String fileUrl;
}
