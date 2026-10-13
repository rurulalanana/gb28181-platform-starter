package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单条 ID 应答（新增 / 保存后回传主键）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdVo {

    /** 主键。 */
    private String id;

    /** 主键（数值型，用户接口使用）。 */
    private Long userId;

    /** 只带字符串主键。 */
    public static IdVo of(String id) {
        IdVo vo = new IdVo();
        vo.id = id;
        return vo;
    }

    /** 只带用户主键。 */
    public static IdVo ofUserId(Long userId) {
        IdVo vo = new IdVo();
        vo.userId = userId;
        return vo;
    }
}
