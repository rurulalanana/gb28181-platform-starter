package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.util.List;

/**
 * 接入控制规则列表（出参）。
 *
 * <p>按项目约定：列表统一为 {@code xxxCount + xxxList}。
 * 额外带上两类名单的条数，是为了让前端能提示
 * 「白名单为空 = 不限制」这个反直觉的语义 —— 不看条数就很容易
 * 以为自己已经开了白名单，实际一条规则都没有、全部放行。
 */
@Data
public class AclListVo {

    /** 本次返回的条数。 */
    private int aclCount;

    /** 规则列表。 */
    private List<AclVo> aclList;

    /** 黑名单总条数。 */
    private long blackCount;

    /** 白名单总条数（为 0 表示白名单未启用，等于不限制来源）。 */
    private long whiteCount;
}
