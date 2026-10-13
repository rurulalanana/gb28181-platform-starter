package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 图像抓拍配置应答。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SnapshotConfigVo {

    /** 本次抓拍会话 ID。 */
    private String sessionId;

    /** 抓拍图片列表（由设备异步回传）。 */
    private List<String> snapShotList;

    /** 下载地址列表。 */
    private List<String> downloadList;

    /** 说明。 */
    private String note;
}
