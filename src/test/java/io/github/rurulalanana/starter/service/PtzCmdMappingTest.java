package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.PtzEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 国标 PTZ 指令码 → SDK 方向词的映射。
 *
 * <p><b>这组断言来自一次真实故障</b>：聚焦/光圈的指令码 {@code 0x41/0x42/0x44/0x48}
 * 低位与方向位重合，早期只做位与判断，于是
 * 「点光圈云台上下动、点聚焦云台左右动」。
 * 这类错误不会抛异常、日志也全是成功，只能靠断言钉住。
 */
class PtzCmdMappingTest {

    /** 用真实编码器生成 16 字符报文，测的就是线上真正发出去的那串。 */
    private static String encode(int command, int data1, int data2) {
        return PtzEncoder.encode(1, command, data1, data2, 0);
    }

    @Test
    @DisplayName("聚焦/光圈不得被误判成方向")
    void focusIrisMustNotBecomeDirection() {
        // 聚焦远 0x41 的 bit0 与「右」重合
        assertEquals("focusfar", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.FOCUS_FAR, 129, 0)));
        // 聚焦近 0x42 的 bit1 与「左」重合
        assertEquals("focusnear", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.FOCUS_NEAR, 129, 0)));
        // 光圈放大 0x44 的 bit2 与「下」重合
        assertEquals("irisin", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.IRIS_IN, 0, 129)));
        // 光圈缩小 0x48 的 bit3 与「上」重合
        assertEquals("irisout", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.IRIS_OUT, 0, 129)));
    }

    @Test
    @DisplayName("四个方向与四个斜向")
    void directions() {
        assertEquals("up", ControlService.ptzCmdToSdkCommand(encode(PtzEncoder.UP, 129, 129)));
        assertEquals("down", ControlService.ptzCmdToSdkCommand(encode(PtzEncoder.DOWN, 129, 129)));
        assertEquals("left", ControlService.ptzCmdToSdkCommand(encode(PtzEncoder.LEFT, 129, 129)));
        assertEquals("right", ControlService.ptzCmdToSdkCommand(encode(PtzEncoder.RIGHT, 129, 129)));

        assertEquals("upleft", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.UP | PtzEncoder.LEFT, 129, 129)));
        assertEquals("upright", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.UP | PtzEncoder.RIGHT, 129, 129)));
        assertEquals("downleft", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.DOWN | PtzEncoder.LEFT, 129, 129)));
        assertEquals("downright", ControlService.ptzCmdToSdkCommand(
                encode(PtzEncoder.DOWN | PtzEncoder.RIGHT, 129, 129)));
    }

    @Test
    @DisplayName("变倍与停止")
    void zoomAndStop() {
        assertEquals("zoomin", ControlService.ptzCmdToSdkCommand(
                PtzEncoder.direction(1, PtzEncoder.ZOOM_IN, 0, 8)));
        assertEquals("zoomout", ControlService.ptzCmdToSdkCommand(
                PtzEncoder.direction(1, PtzEncoder.ZOOM_OUT, 0, 8)));
        assertEquals("stop", ControlService.ptzCmdToSdkCommand(PtzEncoder.stop(1)));
    }

    @Test
    @DisplayName("预置位/巡航/辅助开关不在 SDK 可映射范围，必须返回 null 而不是某个方向")
    void unsupportedFamiliesReturnNull() {
        // 0x81 的低位是 01，早期会被当成「右」；设置预置位会让镜头转走
        assertNull(ControlService.ptzCmdToSdkCommand(
                PtzEncoder.preset(1, PtzEncoder.PRESET_SET, 5)));
        assertNull(ControlService.ptzCmdToSdkCommand(
                PtzEncoder.preset(1, PtzEncoder.PRESET_CALL, 5)));
        assertNull(ControlService.ptzCmdToSdkCommand(
                PtzEncoder.preset(1, PtzEncoder.PRESET_REMOVE, 5)));
        assertNull(ControlService.ptzCmdToSdkCommand(
                PtzEncoder.cruiseStart(1, 1)));
        assertNull(ControlService.ptzCmdToSdkCommand(
                PtzEncoder.auxiliary(1, 1, true)));
    }

    @Test
    @DisplayName("非法输入按停止处理，不猜方向")
    void malformedInputIsStop() {
        assertEquals("stop", ControlService.ptzCmdToSdkCommand(null));
        assertEquals("stop", ControlService.ptzCmdToSdkCommand(""));
        assertEquals("stop", ControlService.ptzCmdToSdkCommand("ABC"));
        assertEquals("stop", ControlService.ptzCmdToSdkCommand("不是十六进制"));
    }
}
