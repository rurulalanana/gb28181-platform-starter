package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道预置位列表（设备侧真实存在的预置位）。
 *
 * <p><b>为什么要单独一个 VO，而不是复用 {@code gb_preset} 实体</b>：
 * 两者来源不同、语义也不同。
 * <ul>
 *   <li>{@code gb_preset} 是<b>平台侧的自定义名称表</b> —— 设备只存编号，
 *       用户改的名字由平台维护（{@code POST /device/setpresetname}）；</li>
 *   <li>本 VO 是<b>设备侧的真实预置位</b> —��� 通过 MANSCDP
 *       {@code PresetQuery} 查询得到，包含编号、设备上的名字、状态。</li>
 * </ul>
 * 所以这里做一次合并：编号以设备为准，名称优先用平台自定义的，
 * 没有自定义时回落到设备上报的。
 */
@Data
public class PresetListVo {

    /** 通道国标编码。 */
    private String channelId;

    /** 预置位个数。 */
    private Integer count;

    /**
     * 向设备发起查询的时间（{@code yyyy-MM-dd HH:mm:ss}）。
     *
     * <p>{@code null} 表示<b>从未查到过</b> —— 前端据此区分
     * 「设备返回了空列表」与「还没查」，两者要给的提示完全不同。
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

        /** 设备上报的原始名称；空表示设备没给。 */
        private String deviceName;

        /** 是否为平台自定义名称。 */
        private Boolean customName;

        /** 设备上报的状态（原样透传，各厂商含义不一）。 */
        private String status;
    }
}
