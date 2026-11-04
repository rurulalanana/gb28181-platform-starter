package io.github.rurulalanana.starter.web;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.service.LoginGuardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 行为验证码（免登录）。
 *
 * <p>本组必须免登录：它存在的目的就是保护登录接口。
 *
 * <p><b>返回体用本项目统一信封</b>（{@code {code,msg,data}}，成功 code=0），
 * 而不是 tianai 原生的 {@code {code:200,...}} —— 前端 http 拦截器只认 code=0，
 * 原生结构会被判成失败、把整个响应丢掉。
 */
@Tag(name = "行为验证码", description = "滑块验证码，登录前使用（免登录）")
@RestController
@RequestMapping("/api/v1/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final LoginGuardService loginGuard;

    /**
     * 本次部署是否需要验证码（免登录）。
     *
     * <p><b>为什么让前端问一次而不是把它写死</b>：{@code gb28181.login.captcha-enabled}
     * 是运维开关，本地调试或验证码组件故障时会被关掉。前端若无条件弹滑块，
     * 关掉开关后仍然卡在「拖动滑块」，而接口其实根本不校验 —— 表现为「验证码坏了登不进去」，
     * 与真实原因（开关已关）完全对不上。所以由后端说了算。
     */
    @Operation(summary = "是否启用验证码", description = "前端据此决定登录前要不要弹滑块")
    @GetMapping("/required")
    public io.github.rurulalanana.starter.common.ApiResponse<Map<String, Boolean>> required() {
        return io.github.rurulalanana.starter.common.ApiResponse.ok(
                Map.of("required", loginGuard.captchaRequired()));
    }

    @Operation(summary = "生成验证码",
            description = "type 可选 SLIDER / ROTATE / CONCAT / WORD_IMAGE_CLICK / POW，默认 SLIDER")
    @PostMapping("/gen")
    public io.github.rurulalanana.starter.common.ApiResponse<ImageCaptchaVO> gen(
            @RequestParam(required = false) String type) {
        ApiResponse<ImageCaptchaVO> res = loginGuard.generate(type);
        if (res == null || !res.isSuccess() || res.getData() == null) {
            throw new BizException(500, res == null ? "验证码生成失败" : res.getMsg());
        }
        return io.github.rurulalanana.starter.common.ApiResponse.ok(res.getData());
    }

    /**
     * 校验滑动轨迹。
     *
     * <p>成功后返回的 {@code data.id} 即登录要带的 {@code captchaId}（一次性）。
     */
    @Operation(summary = "校验滑动轨迹", description = "通过后把返回的 id 作为登录请求的 captchaId")
    @PostMapping("/check")
    public io.github.rurulalanana.starter.common.ApiResponse<Map<String, String>> check(
            @RequestBody CaptchaCheckRequest req) {
        if (req == null || req.getId() == null || req.getId().isBlank()) {
            throw BizException.badRequest("缺少验证码 id");
        }
        String id = loginGuard.checkTrack(req.getId(), req.getData());
        return io.github.rurulalanana.starter.common.ApiResponse.ok(Map.of("id", id));
    }

    /** 前端回传的轨迹数据。 */
    @Data
    public static class CaptchaCheckRequest {
        /** 生成时为验证码分配的 id。 */
        private String id;
        /** 拖动轨迹（含背景图尺寸、模板尺寸、起止时间与轨迹点）。 */
        private ImageCaptchaTrack data;
    }
}
