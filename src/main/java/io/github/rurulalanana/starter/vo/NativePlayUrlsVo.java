package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：播放地址集合。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativePlayUrlsVo {

    private String httpFlv;
    private String wsFlv;
    private String hls;
    private String webrtc;
    private String rtsp;
    private String rtmp;
}
