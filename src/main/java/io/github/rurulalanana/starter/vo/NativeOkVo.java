package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 原生接口：通用操作结果（{@code POST /api/stop/...} 等）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NativeOkVo {

    private Boolean ok;
    private String deviceId;
    private String recordId;
    private String file;
    private String command;
    private String message;

    /** 简易构造：只带 ok。 */
    public static NativeOkVo of(Boolean ok) {
        NativeOkVo vo = new NativeOkVo();
        vo.ok = ok;
        return vo;
    }
}
