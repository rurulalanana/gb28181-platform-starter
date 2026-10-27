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

    /**
     * 设备是否真的应答了本次检索。
     *
     * <p><b>必须与「没有录像」区分开</b>：设备不应答（离线、设备侧检索故障、
     * SIP 超时）与「这段时间确实没录」在数据上都是空列表。
     * 前端把前者显示成「当日无录像」就是在报假警 —— 用户会跑去现场
     * 确认一台其实好好的设备。
     */
    private Boolean deviceAnswered;

    /** 列表内容是否来自本地缓存（设备未应答时的降级，可能过期）。 */
    private Boolean fromCache;

    /** 给用户看的说明（仅设备未应答时才有）。 */
    private String notice;
}
