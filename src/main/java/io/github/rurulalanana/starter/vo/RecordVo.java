package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 设备侧录像段（{@code playback/recordlist}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordVo {

    private String deviceId;
    private String name;

    /** 设备侧文件路径（回放时回传该值）。 */
    private String filePath;

    private String address;

    /** 开始时间（ISO）。 */
    private String startTime;

    /** 结束时间（ISO）。 */
    private String endTime;

    private Long fileSize;

    /** 录像类型：{@code time} / {@code alarm} / {@code manual}。 */
    private String type;

    /** 保密级别。 */
    private Integer secrecy;
}
