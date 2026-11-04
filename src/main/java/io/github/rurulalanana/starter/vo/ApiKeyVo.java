package io.github.rurulalanana.starter.vo;

import lombok.Data;

/**
 * API 密钥状态。
 *
 * <p>{@code key} 只在「生成」那一刻有值，之后永远为 null —— 库里只存哈希，
 * 服务端也拿不回明文。用户丢了只能重新生成。
 */
@Data
public class ApiKeyVo {

    /** 明文密钥，仅生成接口返回一次。 */
    private String key;

    /** 前 8 位，供用户辨认。 */
    private String hint;

    /** 生成时间。 */
    private String createdAt;

    /** 是否已配置（有密钥时为 true）。 */
    private Boolean enabled;
}
