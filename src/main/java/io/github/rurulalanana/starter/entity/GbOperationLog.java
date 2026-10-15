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

    private LocalDateTime createTime;
}
