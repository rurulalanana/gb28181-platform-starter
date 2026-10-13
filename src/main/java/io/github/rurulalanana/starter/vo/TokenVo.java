package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 登录 / 改密后签发的 Token。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenVo {

    /** Cookie 方式使用的 Token。 */
    private String cookieToken;

    /** URL 参数方式使用的 Token。 */
    private String urlToken;

    /** Token 值（与 {@link #cookieToken} 相同，便于前端直接取用）。 */
    private String token;

    /** 别名，兼容部分客户端。 */
    private String authToken;

    /** 有效期（秒）。 */
    private Long tokenTimeout;
}
