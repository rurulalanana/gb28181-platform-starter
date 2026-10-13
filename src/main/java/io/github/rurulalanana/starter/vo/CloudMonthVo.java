package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 按月录像标记（{@code cloudrecord/querymonthly}）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudMonthVo {

    /** 当月每天是否有录像的 0/1 串。 */
    private String flag;
}
