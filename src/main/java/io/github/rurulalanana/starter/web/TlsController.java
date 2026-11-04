package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.service.TlsCertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTPS 证书管理接口（{@code /api/v1/tls/*}）：status / install / regenerate / disable /
 * gm-readiness / probe。
 *
 * <p><b>只有管理员可用</b>：这里改整站传输层配置，一次误操作就让所有用户无法访问；
 * install 会写入私钥，权限等同于「能冒充本站」。
 * <b>私钥不会被读回</b>：任何接口都不返回私钥，一旦返回就进了浏览器缓存与接口日志。
 */
@Slf4j
@RestController
@Tag(name = "HTTPS 与国密", description = "证书安装、状态、国密就绪度")
@RequestMapping("/api/v1/tls")
@RequiredArgsConstructor
public class TlsController {

    private final TlsCertificateService tlsService;

    @Operation(summary = "当前 HTTPS 状态")
    @GetMapping("/status")
    public ApiResponse<TlsCertificateService.TlsStatus> status() {
        StpUtil.checkLogin();
        return ApiResponse.ok(tlsService.status());
    }

    /**
     * 安装证书并启用 HTTPS。用 POST body 而非表单参数：证书链 PEM 常 5~10KB，
     * 塞进 URL/表单字段容易在中间环节被截断。
     */
    @Operation(summary = "安装证书并启用 HTTPS")
    @PostMapping("/install")
    public ApiResponse<TlsCertificateService.TlsStatus> install(
            @RequestBody TlsInstallRequest request) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        // 只记录长度，绝不把证书/私钥内容写进日志
        log.info("收到 HTTPS 证书安装请求：证书 {} 字节，私钥 {} 字节",
                request == null || request.getCert() == null ? 0 : request.getCert().length(),
                request == null || request.getKey() == null ? 0 : request.getKey().length());
        if (request == null) {
            throw io.github.rurulalanana.starter.common.BizException.badRequest("请求内容为空");
        }
        return ApiResponse.ok(tlsService.install(request.getCert(), request.getKey()));
    }

    @Operation(summary = "停用 HTTPS")
    @PostMapping("/disable")
    public ApiResponse<TlsCertificateService.TlsStatus> disable() {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        log.warn("收到停用 HTTPS 请求");
        return ApiResponse.ok(tlsService.disable());
    }

    /**
     * 按当前配置重新生成 nginx 配置并 reload（不重传证书）。主要用途是改端口：
     * 重传意味着私钥再过一遍手，经手次数越多泄漏风险越大。
     */
    @Operation(summary = "按当前配置重新生成 nginx 配置并 reload（不重传证书）")
    @PostMapping("/regenerate")
    public ApiResponse<TlsCertificateService.TlsStatus> regenerate() {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        return ApiResponse.ok(tlsService.regenerate());
    }

    /**
     * 国密 35114 就绪度自检：只读检测，回答「要上 35114 还缺什么」，
     * 不提供「启用国密」开关（原因见 {@link TlsCertificateService#gmReadiness()}）。
     */
    @Operation(summary = "国密 35114 就绪度自检")
    @GetMapping("/gm-readiness")
    public ApiResponse<TlsCertificateService.GmReadiness> gmReadiness() {
        StpUtil.checkLogin();
        return ApiResponse.ok(tlsService.gmReadiness());
    }

    /**
     * 检测 HTTPS 端口能否从公网访问，专治「本机 curl 通、浏览器打不开」——九成是云安全组没放通。
     * 检测走公网 IP，云环境下会经过网关，因而能命中安全组规则。
     */
    @Operation(summary = "检测 HTTPS 端口能否从公网访问")
    @GetMapping("/probe")
    public ApiResponse<TlsCertificateService.PortProbe> probe(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer port) {
        StpUtil.checkLogin();
        return ApiResponse.ok(tlsService.probe(port));
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
