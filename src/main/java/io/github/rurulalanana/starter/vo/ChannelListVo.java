package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 通道列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelListVo {

    /** 过滤后的通道总数。 */
    private Integer channelCount;

    /** 当前页通道。 */
    private java.util.List<ChannelVo> channelList;
}
