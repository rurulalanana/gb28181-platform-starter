package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 停止直播的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamStopVo {

    /** 是否确实停止了流（流本就不存在时为 false）。 */
    private Boolean ok;

    /** 通道编号。 */
    private String channelId;

    /** 一并释放的转推路数。 */
    private Integer relaysStopped;

    /** 下载模式下 ZLM 落盘的文件路径。 */
    private String filePath;
}
