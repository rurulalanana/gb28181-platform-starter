package io.github.rurulalanana.starter.vo;

import lombok.Data;

import java.io.Serializable;

/** 单条操作日志。 */
@Data
public class OpLogVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 调用方式：HTTP_GET / HTTP_POST / SIP_MESSAGE 等。 */
    private String method;

    /** 操作名称（中文语义，如「登录」「云台控制」；无法识别时省略）。 */
    private String operationName;

    /** 资源路径。 */
    private String resource;

    /** 远端地址。 */
    private String remoteAddr;

    /** 结果（HTTP 状态码或异常名）。 */
    private String result;

    /** 用时（毫秒）。 */
    private Integer durationMs;

    /** 操作人（登录用户名）。 */
    private String operator;

    /**
     * 请求参数（已脱敏、已截断）。
     *
     * <p>这是审计最有价值的一列：「谁在什么时候调了哪个 URL」只能说明「动过」，
     * 而「他把哪一项配置改成了多少」必须靠参数才能回答。
     * 敏感字段（密码/密钥/token）在写入时已替换为 {@code ***(N字符)}。
     */
    private String params;

    /** 时间（yyyy-MM-dd HH:mm:ss）。 */
    private String createTime;
}
