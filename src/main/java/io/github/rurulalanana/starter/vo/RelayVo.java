package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单条转推记录。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelayVo {

    /** 源通道编号。 */
    private String channelId;

    /** 本地流 ID。 */
    private String streamId;

    /** 目标 CDN 地址。 */
    private String cdn;

    /** 启动时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String startAt;
}
