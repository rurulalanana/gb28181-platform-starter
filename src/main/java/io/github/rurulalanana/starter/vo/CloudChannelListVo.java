package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 有云端录像的通道列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudChannelListVo {

    private Integer total;

    private List<CloudChannelVo> rows;
}
