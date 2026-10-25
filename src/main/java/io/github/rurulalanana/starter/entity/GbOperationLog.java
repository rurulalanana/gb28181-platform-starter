package io.github.rurulalanana.starter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志（管理接口调用记录）。
 *
 * <p>对应表 {@code gb_operation_log}。由 {@code OperationLogInterceptor}
 * 拦截 {@code /api/v1/**} 写入，用于排查「谁在什么时候调了什么接口、耗时多久、结果如何」。
 */
@Data
@TableName("gb_operation_log")
public class GbOperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用方式：HTTP_GET / HTTP_POST（SIP_* 为后续信令日志预留）。 */
    private String method;

    /** 资源路径（如 /api/v1/device/remove）。 */
    private String resource;

    /** 远端地址（请求方 IP）。 */
    private String remoteAddr;

    /** 结果：HTTP 状态码（如 200），异常时为异常类名。 */
    private String result;

    /** 用时（毫秒）。 */
    private Integer durationMs;

    /** 操作人（登录用户名；未登录/登录前接口为空）。 */
    private String operator;

    /**
     * 请求参数（已脱敏、已截断）。
     *
     * <p><b>为什么必须记</b>：只记「谁在什么时候调了哪个 URL」回答不了审计真正要问的问题
     * ——「谁把哪个通道的什么改成了什么」。改数据类操作若不留参数，
     * 事后只能确认「动过」，无法确认「改成了多少」，也就无法追责与回滚。
     *
     * <p>密码、私钥、证书、token 等字段会先脱敏再写入（见
     * {@code OperationLogInterceptor#sanitize}）；否则审计日志本身会变成凭据泄漏源。
     */
    private String params;

    private LocalDateTime createTime;
}
