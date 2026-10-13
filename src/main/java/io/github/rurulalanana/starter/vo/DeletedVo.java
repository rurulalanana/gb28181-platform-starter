package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 删除条数统计（通用）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeletedVo {

    /** 删除条数。 */
    private Integer deleted;

    /** 关联通道编号（部分接口返回）。 */
    private String channelId;

    /** 保存条数（部分接口返回）。 */
    private Integer saved;

    /** 移除条数（部分接口返回）。 */
    private Integer removed;

    /** 推送条数（部分接口返回）。 */
    private Integer pushed;

    /** 只带删除条数的简易构造。 */
    public static DeletedVo of(Integer deleted) {
        DeletedVo vo = new DeletedVo();
        vo.deleted = deleted;
        return vo;
    }

    /** 只带保存条数。 */
    public static DeletedVo ofSaved(Integer saved) {
        DeletedVo vo = new DeletedVo();
        vo.saved = saved;
        return vo;
    }

    /** 只带移除条数。 */
    public static DeletedVo ofRemoved(Integer removed) {
        DeletedVo vo = new DeletedVo();
        vo.removed = removed;
        return vo;
    }

    /** 只带推送条数。 */
    public static DeletedVo ofPushed(Integer pushed) {
        DeletedVo vo = new DeletedVo();
        vo.pushed = pushed;
        return vo;
    }
}
