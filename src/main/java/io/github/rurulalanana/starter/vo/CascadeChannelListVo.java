package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 级联通道列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CascadeChannelListVo {

    private Integer channelCount;

    /** 已共享的通道数。 */
    private Integer channelRelateCount;

    /** 是否共享全部通道。 */
    private Boolean shareAllChannel;

    private List<CascadeChannelVo> channelList;
}
