package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.service.TlsCertificateService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTPS 证书管理接口（{@code /api/v1/tls/*}）。
 *
 * <pre>
 * GET  /api/v1/tls/status           当前证书状态（域名、有效期、剩余天数）
 * POST /api/v1/tls/install          上传证书与私钥，校验后生成 nginx 配置并 reload
 * POST /api/v1/tls/disable          停用 HTTPS（保留证书文件）
 * GET  /api/v1/tls/gm-readiness     国密 35114 就绪度自检
 * </pre>
 *
 * <p><b>只有管理员可用</b>：这里能改整站的传输层安全配置，一次误操作
 * 就能让所有用户无法访问。更关键的是 install 会写入私钥，
 * 权限等同于「能冒充本站」。
 *
 * <p><b>私钥不会被读回</b>：出参 {@code TlsStatus} 里只有证书公开信息，
 * 任何接口都不返回私钥内容 —— 一旦返回，它就进了浏览器缓存与接口日志。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tls")
@RequiredArgsConstructor
public class TlsController {

    private final TlsCertificateService tlsService;

    /** 当前 HTTPS 状态。 */
    @GetMapping("/status")
    public ApiResponse<TlsCertificateService.TlsStatus> status() {
        StpUtil.checkLogin();
        return ApiResponse.ok(tlsService.status());
    }

    /**
     * 安装证书并启用 HTTPS。
     *
     * <p>用 POST body 而不是表单参数：证书链 PEM 动辄 5~10KB，
     * 带上中间证书后还会更长，塞进 URL 或表单字段都容易在中间环节被截断。
     */
    @PostMapping("/install")
    public ApiResponse<TlsCertificateService.TlsStatus> install(
            @RequestBody TlsInstallRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        // 只记录长度，绝不把证书/私钥内容写进日志
        log.info("收到 HTTPS 证书安装请求：证书 {} 字节，私钥 {} 字节",
                request == null || request.getCert() == null ? 0 : request.getCert().length(),
                request == null || request.getKey() == null ? 0 : request.getKey().length());
        if (request == null) {
            throw io.github.rurulalanana.starter.common.BizException.badRequest("请求内容为空");
        }
        return ApiResponse.ok(tlsService.install(request.getCert(), request.getKey()));
    }

    /** 停用 HTTPS。 */
    @PostMapping("/disable")
    public ApiResponse<TlsCertificateService.TlsStatus> disable() {
        StpUtil.checkLogin();
        StpUtil.checkRoleOr("admin");
        log.warn("收到停用 HTTPS 请求");
        return ApiResponse.ok(tlsService.disable());
    }

    /**
     * 国密 35114 就绪度自检。
     *
     * <p>只读检测，不改变任何配置 —— 它回答的是「要上 35114 还缺什么」，
     * 而不是提供一个「启用国密」的开关。原因见
     * {@link TlsCertificateService#gmReadiness()}。
     */
    @GetMapping("/gm-readiness")
    public ApiResponse<TlsCertificateService.GmReadiness> gmReadiness() {
        StpUtil.checkLogin();
        return ApiResponse.ok(tlsService.gmReadiness());
    }

    /** 证书安装请求。 */
    @Data
    public static class TlsInstallRequest {
        /** 证书 PEM（可含中间证书链，服务器证书在第一个）。 */
        private String cert;
        /** 私钥 PEM（不含口令）。 */
        private String key;
    }
}
