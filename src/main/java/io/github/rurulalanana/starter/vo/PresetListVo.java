package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道预置位列表（设备侧真实存在的预置位）。不能复用 {@code gb_preset} 实体：后者是平台侧自定义
 * 名称表，本 VO 来自设备 {@code PresetQuery} 应答（编号、设备名、状态）。合并规则：
 * 编号以设备为准，名称优先平台自定义，没有时回落到设备上报。
 */
@Data
public class PresetListVo {

    /** 通道国标编码。 */
    private String channelId;

    private Integer count;

    /**
     * 向设备发起查询的时间（{@code yyyy-MM-dd HH:mm:ss}）。{@code null} 表示<b>从未查到过</b>，
     * 前端据此区分「设备返回了空列表」与「还没查」。
     */
    private String queriedAt;

    /** 预置位明细，按编号升序。 */
    private List<Item> items = new ArrayList<>();

    /** 单个预置位。 */
    @Data
    public static class Item {

        /** 预置位编号（1~255）。 */
        private Integer presetId;

        /** 显示名称（平台自定义优先，否则设备上报）。 */
        private String name;

        private String deviceName;

        private Boolean customName;

        /** 设备上报的状态（原样透传，各厂商含义不一）。 */
        private String status;
    }
}
