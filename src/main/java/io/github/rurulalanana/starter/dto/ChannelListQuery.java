package io.github.rurulalanana.starter.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通道列表查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChannelListQuery extends PageQuery {

    /** 设备国标编号，多条用逗号分隔。 */
    private String serial;

    /** 通道国标编号，多条用逗号分隔。 */
    private String code;

    /** 行政区号。 */
    private String civilcode;

    /** 警区。 */
    private String block;

    /** 通道类型：{@code device} 子设备 / {@code decode} 解码器 / {@code dir} 子目录 / {@code all}。 */
    private String channelType = "all";

    /** 子目录编号。 */
    private String dirSerial;

    /** 在线状态过滤。 */
    private Boolean online;

    /** 是否开启分享。 */
    private Boolean shared;
}
