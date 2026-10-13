package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 抓取类接口的统一应答（{@code device/fetch*}）。
 *
 * <p>这些接口只负责下发 MANSCDP 查询，真正的数据由设备应答后异步落库。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FetchResultVo {

    /** 固定 {@code OK}，表示指令已下发。 */
    private String result;

    /** 目标设备 / 通道编号。 */
    private String deviceId;

    /** 补充说明。 */
    private String message;

    /** 巡航组序号（仅 {@code fetchcruisetrack} 返回）。 */
    private Integer number;

    /** 快捷构造。 */
    public static FetchResultVo ok(String deviceId) {
        FetchResultVo vo = new FetchResultVo();
        vo.result = "OK";
        vo.deviceId = deviceId;
        return vo;
    }

    /** 快捷构造（带说明）。 */
    public static FetchResultVo ok(String deviceId, String message) {
        FetchResultVo vo = ok(deviceId);
        vo.message = message;
        return vo;
    }
}
