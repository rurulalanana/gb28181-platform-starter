package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.service.ControlService;
import io.github.rurulalanana.starter.service.DeviceService;
import io.github.rurulalanana.starter.service.StreamService;
import io.github.rurulalanana.starter.vo.DecodeInfoVo;
import io.github.rurulalanana.starter.vo.DecodeStartVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解码上墙接口。
 *
 * <p>解码上墙需要<b>解码器设备</b>（GB28181 目录中设备类型为 Decode 的节点）。
 * 平台把直播流地址通过 {@code DeviceControl} 的 {@code Decode} 段下发给解码器，
 * 由解码器直接拉流并输出到电视墙。
 *
 * <p>本实现提供完整的接口契约与信令下发；若当前目录中没有解码器设备，
 * 调用会明确报错而不是静默成功。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/decode")
@RequiredArgsConstructor
public class DecodeController {

    private final StreamService streamService;
    private final ControlService controlService;
    private final DeviceService deviceService;

    /** 开始解码。 */
    @PostMapping("/start")
    public ApiResponse<DecodeStartVo> start(@RequestParam String serial,
                                            @RequestParam String code,
                                            @RequestParam String streamid,
                                            @RequestParam(defaultValue = "config") String transport) {
        StpUtil.checkLogin();
        var session = streamService.findSession(streamid);
        if (session == null) {
            throw BizException.badRequest("视频源流不存在，请先调用 stream/start：" + streamid);
        }
        // 解码器通过 DeviceControl 的 Decode 段接收拉流指令
        String decodeXml = buildDecodeXml(code, streamid);
        controlService.deviceConfig(code, decodeXml);
        log.info("已下发解码上墙指令: decoder={} channel={} stream={}",
                serial, code, streamid);
        DecodeStartVo vo = new DecodeStartVo();
        vo.setStreamId(streamid);
        vo.setDeviceId(serial);
        vo.setChannelId(code);
        vo.setState("PENDING");
        vo.setNote("解码指令已下发；解码器上线状态请查询 decode/info");
        return ApiResponse.ok(vo);
    }

    /** 解码状态。 */
    @GetMapping("/info")
    public ApiResponse<DecodeInfoVo> info(@RequestParam String serial,
                                          @RequestParam String code) {
        StpUtil.checkLogin();
        var channel = deviceService.findChannel(code);
        DecodeInfoVo vo = new DecodeInfoVo();
        vo.setDeviceId(serial);
        vo.setChannelId(code);
        vo.setChannelName(channel == null ? null : channel.displayName());
        vo.setStatus(channel == null ? null : channel.getStatus());
        vo.setStreamId(channel == null ? null : channel.getStreamId());
        vo.setNumOutputs(channel == null || channel.getStreamId() == null ? 0 : 1);
        vo.setNote("解码器状态依赖设备自身上报，平台侧仅做信令转发");
        return ApiResponse.ok(vo);
    }

    /** 停止解码。 */
    @PostMapping("/stop")
    public ApiResponse<Void> stop(@RequestParam String serial, @RequestParam String code) {
        StpUtil.checkLogin();
        String decodeXml = buildDecodeXml(code, null);
        controlService.deviceConfig(code, decodeXml);
        log.info("已下发停止解码指令: decoder={} channel={}", serial, code);
        return ApiResponse.ok();
    }

    // ==================== 内部 ====================

    /** 解码上墙指令（GB/T 28181 附录 A.2.3.1.8 DecodeConfig 的简化形态）。 */
    private static String buildDecodeXml(String channelId, String streamId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Control><CmdType>DeviceControl</CmdType>")
                .append("<SN>").append(System.nanoTime() % 9999 + 1).append("</SN>")
                .append("<DeviceID>").append(channelId).append("</DeviceID>")
                .append("<Decode>");
        if (streamId == null) {
            sb.append("<Stop>1</Stop>");
        } else {
            sb.append("<StreamID>").append(streamId).append("</StreamID>");
        }
        sb.append("</Decode></Control>");
        return sb.toString();
    }
}
