package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云端录像「有录像月份标记」出参（GBS 的 {@code queryflags}）。
 *
 * <p>用强类型对象包裹 Map，避免直接返回 {@code Map<String,String>}
 * 违反「出参必须是强类型 POJO」的约定。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudFlagsVo {

    /**
     * 月份 → 当月每天是否有录像的 0/1 串。
     *
     * <p>键为 {@code YYYYMM}，值为长度等于当月天数的字符串，
     * 第 n 个字符为 {@code 1} 表示该月第 n 天有录像。
     */
    private Map<String, String> flags = new LinkedHashMap<>();
}
