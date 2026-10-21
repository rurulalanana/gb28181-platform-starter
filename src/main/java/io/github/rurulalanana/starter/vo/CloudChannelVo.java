package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 云端录像通道（{@code cloudrecord/querychannels}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudChannelVo {

    private String serial;
    private String code;
    private String name;
    private String customName;
    private String status;
    private String deviceName;
    private String deviceCustomName;
    private Boolean deviceOnline;
    private String updatedAt;
    private String createdAt;

    /**
     * 快照访问路径（可直接放进 {@code <img src>}）。
     *
     * <p>没有快照时为 {@code null}（前端显示占位图）。路径形态与 LiveGBS 的
     * {@code snap_url} 对齐，便于对照。
     */
    private String snapUrl;
}
