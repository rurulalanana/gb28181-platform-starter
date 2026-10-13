package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 按日查询云端录像的结果。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudDailyVo {

    /** 通道展示名。 */
    private String name;

    private List<CloudRecordVo> list;
}
