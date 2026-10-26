package io.github.rurulalanana.starter.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PTZ 方向位与 GBK 乱码修复的回归测试。
 *
 * <p>这两处都曾因「按直觉写」而在现场出错，且都不会被编译期发现，
 * 因此用断言把正确值钉住。
 */
class PtzEncoderAndCharsetFixTest {

    // ==================== PTZ 方向位 ====================

    /**
     * 方向位与 wvp-GB28181-pro 实测实现一致。
     *
     * <p>wvp 的 {@code SourcePTZServiceForGbImpl}：
     * {@code pan==0(左)→1<<1}、{@code pan==1(右)→1<<0}、
     * {@code tilt==0(上)→1<<3}、{@code tilt==1(下)→1<<2}。
     *
     * <p>方向位与直觉相反：低位是右/下，高位是左/上。
     * 早期版本按「左=1 右=2 上=4 下=8」的直觉编码，
     * 现场表现为四个方向全部反向（左右互换、上下互换，而变倍正常）。
     */
    @Test
    @DisplayName("PTZ 方向位对齐 wvp 与标准表 A.3")
    void directionBitsMatchStandard() {
        assertEquals(1 << 1, PtzEncoder.LEFT, "左应为 Bit2(0x02)");
        assertEquals(1 << 0, PtzEncoder.RIGHT, "右应为 Bit1(0x01)");
        assertEquals(1 << 3, PtzEncoder.UP, "上应为 Bit4(0x08)");
        assertEquals(1 << 2, PtzEncoder.DOWN, "下应为 Bit3(0x04)");
        assertEquals(1 << 4, PtzEncoder.ZOOM_IN, "放大应为 Bit5(0x10)");
        assertEquals(1 << 5, PtzEncoder.ZOOM_OUT, "缩小应为 Bit6(0x20)");
    }

    @Test
    @DisplayName("斜向组合位")
    void diagonalCombinations() {
        assertEquals(0x0A, PtzEncoder.UP | PtzEncoder.LEFT, "左上 = 0x08|0x02");
        assertEquals(0x09, PtzEncoder.UP | PtzEncoder.RIGHT, "右上 = 0x08|0x01");
        assertEquals(0x06, PtzEncoder.DOWN | PtzEncoder.LEFT, "左下 = 0x04|0x02");
        assertEquals(0x05, PtzEncoder.DOWN | PtzEncoder.RIGHT, "右下 = 0x04|0x01");
    }

    /**
     * 报文结构：{@code A5 0F 01 <cmd> <数据1> <数据2> <数据3<<4|地址高4> <校验和>}，
     * 校验和 = 前 7 字节算术和对 256 取模。
     */
    @Test
    @DisplayName("报文字节与校验和")
    void messageBytes() {
        assertEquals("A50F0100000000B5", PtzEncoder.stop(1), "stop 校验和 = A5+0F+01 = B5");
        assertEquals("A50F0102818100B9", PtzEncoder.direction(1, PtzEncoder.LEFT, 129));
        assertEquals("A50F0101818100B8", PtzEncoder.direction(1, PtzEncoder.RIGHT, 129));
        assertEquals("A50F0108818100BF", PtzEncoder.direction(1, PtzEncoder.UP, 129));
        assertEquals("A50F0104818100BB", PtzEncoder.direction(1, PtzEncoder.DOWN, 129));
    }

    /**
     * 扩展指令码必须与标准 A.3.1 / wvp 一致。
     *
     * <p><b>这组断言来自一次真实故障</b>：早期实现按「Bit7=1 进入扩展模式」自行推导，
     * 把预置位写成 {@code 0x60/0x50/0x48}，并把编号放进字节5。
     * 设备收到后在 SIP 层回 200 OK，但镜头一动不动 ——
     * 因为 {@code 0x50} 在标准里根本不是预置位指令。报文本身合法，抓包看不出问题。
     *
     * <p>正确值：预置位 {@code 0x81/0x82/0x83}，<b>编号在字节6</b>；
     * 字节5 固定填 1（与 wvp 的 {@code frontEndCommand(..., 0x82, 1, presetId, 0)} 一致）。
     */
    @Test
    @DisplayName("预置位指令码 0x81/0x82/0x83，编号在字节6")
    void presetCodesMatchStandard() {
        assertEquals(0x81, PtzEncoder.PRESET_SET, "设置预置位");
        assertEquals(0x82, PtzEncoder.PRESET_CALL, "调用预置位");
        assertEquals(0x83, PtzEncoder.PRESET_REMOVE, "删除预置位");

        // 调用预置位 2：A5 0F 01 82 01 02 00 3A
        assertEquals("A50F01820102003A", PtzEncoder.preset(1, PtzEncoder.PRESET_CALL, 2));
        // 设置预置位 255
        assertEquals("A50F018101FF0036", PtzEncoder.preset(1, PtzEncoder.PRESET_SET, 255));
    }

    /** 聚焦 0x41/0x42、光圈 0x44/0x48，与变倍 0x10/0x20 完全无关。 */
    @Test
    @DisplayName("聚焦/光圈指令码独立于变倍")
    void focusIrisCodes() {
        assertEquals(0x42, PtzEncoder.FOCUS_NEAR);
        assertEquals(0x41, PtzEncoder.FOCUS_FAR);
        assertEquals(0x44, PtzEncoder.IRIS_IN);
        assertEquals(0x48, PtzEncoder.IRIS_OUT);

        // 光圈放大、速度 129：字节4 = 0x44，速度在字节6
        assertEquals("A50F01440081007A", PtzEncoder.fi(1, PtzEncoder.IRIS_IN, 0, 129));
        // 聚焦近、速度 129：字节4 = 0x42，速度在字节5
        assertEquals("A50F014281000078", PtzEncoder.fi(1, PtzEncoder.FOCUS_NEAR, 129, 0));
        // 变倍放大、变倍速度 8：字节4 = 0x10，速度在字节7 高 4 位 —— 与前两者毫无重叠
        assertEquals("A50F011000008045",
                PtzEncoder.direction(1, PtzEncoder.ZOOM_IN, 0, 8));
    }

    /** 巡航 / 辅助开关指令码。 */
    @Test
    @DisplayName("巡航与辅助开关指令码")
    void cruiseAndAuxCodes() {
        assertEquals(0x84, PtzEncoder.CRUISE_POINT_ADD);
        assertEquals(0x85, PtzEncoder.CRUISE_POINT_DEL);
        assertEquals(0x86, PtzEncoder.CRUISE_SPEED);
        assertEquals(0x87, PtzEncoder.CRUISE_TIME);
        assertEquals(0x88, PtzEncoder.CRUISE_START);
        assertEquals(0x8C, PtzEncoder.AUX_ON);
        assertEquals(0x8D, PtzEncoder.AUX_OFF);

        // 加入巡航点：组 1、预置位 2
        assertEquals("A50F01840102003C", PtzEncoder.cruisePoint(1, true, 1, 2));
        // 雨刷开：开关编号固定 1，在字节5
        assertEquals("A50F018C01000042", PtzEncoder.auxiliary(1, 1, true));
    }

    // ==================== GBK 乱码修复 ====================

    /** 模拟解码器首次尝试：把 GBK 字节按 UTF-8 解码。 */
    private static String asUtf8(String text) {
        return new String(text.getBytes(Charset.forName("GBK")), StandardCharsets.UTF_8);
    }

    /**
     * 「平台」的 GBK 字节 {@code C6 BD CC A8} 恰好是合法 UTF-8
     * （解出 {@code U+01BD ƽ} + {@code U+0328}），不含替换字符，
     * 故解码器不会回退 GBK —— 这是需要 {@link CharsetFix} 兜底的情形。
     */
    @Test
    @DisplayName("修复 GBK 字节恰为合法 UTF-8 的漏网乱码")
    void repairsMojibakeThatDecoderMisses() {
        String broken = asUtf8("平台");
        assertEquals("平台", CharsetFix.repair(broken));
    }

    /**
     * 多数 GBK 汉字序列不是合法 UTF-8，解码器会检测到替换字符并正确回退 GBK，
     * 这类文本不会以乱码形式到达 {@link CharsetFix}，应原样返回。
     */
    @Test
    @DisplayName("库已回退 GBK 的文本不被二次改动")
    void leavesDecoderHandledTextAlone() {
        for (String name : new String[]{"南坑水库", "温泉县坝", "海康威视", "浙江大华技术股份"}) {
            String raw = asUtf8(name);
            assertEquals(raw, CharsetFix.repair(raw), name + " 应原样返回");
        }
    }

    @Test
    @DisplayName("正常 UTF-8 中文不被改动")
    void leavesValidChineseAlone() {
        assertEquals("平台", CharsetFix.repair("平台"));
        assertEquals("万科松花湖度假村西沟水库坝上", CharsetFix.repair("万科松花湖度假村西沟水库坝上"));
        assertEquals("摄像头 Cam-01", CharsetFix.repair("摄像头 Cam-01"));
    }

    /**
     * 合法外文名不能被误判为乱码。
     *
     * <p>判据要求「全部非 ASCII 字符都落在 {@code U+0080~U+036F}、至少 2 个、且为偶数」，
     * 因此：单重音字母（{@code Café}）被数量约束排除；
     * 希腊/西里尔字母（{@code Αθήνα}、{@code Москва}）被码点上界排除。
     * 若不设这些约束，{@code Αθήνα} 会被错误「还原」成 {@code 铻掕儍钀庤皳浼}。
     */
    @Test
    @DisplayName("合法外文名不被误改")
    void leavesForeignNamesAlone() {
        assertEquals("EasyIPC", CharsetFix.repair("EasyIPC"));
        assertEquals("Caf\u00e9", CharsetFix.repair("Caf\u00e9"));
        assertEquals("M\u00fcller", CharsetFix.repair("M\u00fcller"));
        assertEquals("Москва", CharsetFix.repair("Москва"));
        assertEquals("Αθήνα", CharsetFix.repair("Αθήνα"));
    }

    @Test
    @DisplayName("空值安全")
    void handlesNullAndEmpty() {
        assertNull(CharsetFix.repair(null));
        assertEquals("", CharsetFix.repair(""));
        assertNotNull(CharsetFix.repair("中"));
    }

    private static void assertNull(Object actual) {
        org.junit.jupiter.api.Assertions.assertNull(actual);
    }
}
