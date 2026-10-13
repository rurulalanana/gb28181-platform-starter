package io.github.rurulalanana.starter.common;

/**
 * GB/T 28181 PTZ 指令编码器（标准附录 A.3 / 表 A.3）。
 *
 * <h3>为什么不用 {@code PtzCommand}</h3>
 * 依赖模块的 {@code PtzCommand} 只覆盖「方向 + 变倍」这一部分，且：
 * <ul>
 *   <li>预置位编号被写进<b>数据3 的低 4 位</b>，只能表达 0~15，而标准要求 1~255；</li>
 *   <li>预置位「设置 / 调用 / 删除」三种操作被编码成同一个 {@code 0x40}，无法区分；</li>
 *   <li>巡航、看守位、辅助开关（雨刷 / 补光）没有对应编码。</li>
 * </ul>
 * 本类按标准补齐这些指令。方向与变倍仍可直接用 {@code PtzCommand}。
 *
 * <h3>报文结构（8 字节，编码为 16 个十六进制字符）</h3>
 * <pre>
 *   字节1  字节2        字节3    字节4    字节5   字节6   字节7        字节8
 *   0xA5   版本+校验位   地址低8  指令码   数据1   数据2   数据3+地址高4  校验码
 * </pre>
 * <ul>
 *   <li>字节2 = {@code (版本 << 4) | 校验位}，版本取 0 时恒为 {@code 0x0F}</li>
 *   <li>字节5（数据1）：水平速度 / 预置位编号 / 巡航组号</li>
 *   <li>字节6（数据2）：垂直速度 / 巡航组内预置位 / 巡航参数值</li>
 *   <li>字节7（数据3 高 4 位）：变倍速度</li>
 *   <li>字节8 = 前 7 字节算术和对 256 取模</li>
 * </ul>
 *
 * <h3>字节4 指令码</h3>
 * <table border="1">
 *   <caption>指令位定义</caption>
 *   <tr><th>位</th><th>值</th><th>Bit7=0 时含义</th><th>Bit7=1 时含义</th></tr>
 *   <tr><td>Bit7</td><td>0x40</td><td>置 1 表示进入预置位/巡航/看守位模式</td><td>—</td></tr>
 *   <tr><td>Bit6</td><td>0x20</td><td>变倍缩小</td><td>设置预置位</td></tr>
 *   <tr><td>Bit5</td><td>0x10</td><td>变倍放大</td><td>调用预置位</td></tr>
 *   <tr><td>Bit4</td><td>0x08</td><td>云台下</td><td>删除预置位</td></tr>
 *   <tr><td>Bit3</td><td>0x04</td><td>云台上</td><td>巡航设置 / 看守位使能</td></tr>
 *   <tr><td>Bit2</td><td>0x02</td><td>云台右</td><td>巡航开始</td></tr>
 *   <tr><td>Bit1</td><td>0x01</td><td>云台左</td><td>巡航停止</td></tr>
 * </table>
 *
 * <p>标准约束：Bit2/Bit1、Bit4/Bit3、Bit6/Bit5 不可同时为 1。
 */
public final class PtzEncoder {

    /** 固定首字节。 */
    private static final int HEADER = 0xA5;

    // ---------- Bit7=0：方向与变倍 ----------
    /** 云台左。 */
    public static final int LEFT = 0x01;
    /** 云台右。 */
    public static final int RIGHT = 0x02;
    /** 云台上。 */
    public static final int UP = 0x04;
    /** 云台下。 */
    public static final int DOWN = 0x08;
    /** 变倍放大。 */
    public static final int ZOOM_IN = 0x10;
    /** 变倍缩小。 */
    public static final int ZOOM_OUT = 0x20;

    // ---------- Bit7=1：预置位 / 巡航 / 看守位 ----------
    /** 预置位、巡航、看守位操作标志位。 */
    public static final int PRESET_MODE = 0x40;
    /** 设置预置位（{@code PRESET_MODE | SET}）。 */
    public static final int PRESET_SET = PRESET_MODE | 0x20;
    /** 调用预置位（{@code PRESET_MODE | CALL}）。 */
    public static final int PRESET_CALL = PRESET_MODE | 0x10;
    /** 删除预置位（{@code PRESET_MODE | REMOVE}）。 */
    public static final int PRESET_REMOVE = PRESET_MODE | 0x08;
    /** 巡航参数设置。 */
    public static final int CRUISE_SET = PRESET_MODE | 0x04;
    /** 巡航开始。 */
    public static final int CRUISE_START = PRESET_MODE | 0x02;
    /** 巡航停止。 */
    public static final int CRUISE_STOP = PRESET_MODE | 0x01;
    /** 看守位使能。 */
    public static final int WATCHDOG = PRESET_MODE | 0x04;

    private PtzEncoder() {
    }

    /**
     * 编码一条 PTZ 指令。
     *
     * @param address 云台地址（1~4095），直连设备通常填 1
     * @param command 字节4 指令码
     * @param data1   字节5 数据1（水平速度 / 预置位编号 / 巡航组号）
     * @param data2   字节6 数据2（垂直速度 / 巡航组内预置位 / 参数值）
     * @param data3   字节7 高 4 位数据3（变倍速度 0~15）
     * @return 16 个十六进制字符
     */
    public static String encode(int address, int command, int data1, int data2, int data3) {
        if (address < 0 || address > 0xFFF) {
            throw new IllegalArgumentException("云台地址必须在 000H~FFFH(0~4095)：" + address);
        }
        int b1 = HEADER;
        // 字节2：版本 0 + 校验位 (0x0A + 0x05 + 0) % 16 = 15
        int b2 = 0x0F;
        int b3 = address & 0xFF;
        int b4 = command & 0xFF;
        int b5 = data1 & 0xFF;
        int b6 = data2 & 0xFF;
        int b7 = ((data3 & 0x0F) << 4) | ((address >> 8) & 0x0F);
        int b8 = (b1 + b2 + b3 + b4 + b5 + b6 + b7) & 0xFF;
        return String.format("%02X%02X%02X%02X%02X%02X%02X%02X",
                b1, b2, b3, b4, b5, b6, b7, b8);
    }

    /**
     * 方向 / 变倍控制。
     *
     * @param command 由 {@link #LEFT} 等按位或组合
     * @param speed   速度 0~255
     */
    public static String direction(int address, int command, int speed) {
        int sp = Math.max(0, Math.min(255, speed));
        return encode(address, command, sp, sp, 0);
    }

    /** 云台停止（清空所有运动位）。 */
    public static String stop(int address) {
        return encode(address, 0, 0, 0, 0);
    }

    /**
     * 预置位操作。
     *
     * @param operation {@link #PRESET_SET} / {@link #PRESET_CALL} / {@link #PRESET_REMOVE}
     * @param presetIndex 预置位编号 1~255，写入字节5（数据1）
     */
    public static String preset(int address, int operation, int presetIndex) {
        if (presetIndex < 1 || presetIndex > 255) {
            throw new IllegalArgumentException("预置位编号必须在 1~255 之间：" + presetIndex);
        }
        return encode(address, operation, presetIndex, 0, 0);
    }

    /**
     * 巡航控制。
     *
     * @param operation {@link #CRUISE_SET} / {@link #CRUISE_START} / {@link #CRUISE_STOP}
     * @param groupId   巡航组号 1~255
     * @param preset    巡航组内预置位编号，{@code 0} 表示操作整组
     */
    public static String cruise(int address, int operation, int groupId, int preset) {
        return encode(address, operation, groupId & 0xFF, preset & 0xFF, 0);
    }

    /** 巡航参数（速度 / 停留时间）设置：值写在字节5。 */
    public static String cruiseParam(int address, int operation, int groupId, int value) {
        return encode(address, operation, groupId & 0xFF, value & 0xFF, (value >> 8) & 0x0F);
    }

    /**
     * 看守位控制。
     *
     * @param enabled  是否开启
     * @param resetTime 自动归位时间间隔（秒），最大 255
     * @param presetIndex 归位调用的预置位编号
     */
    public static String watchdog(int address, boolean enabled, int resetTime, int presetIndex) {
        int cmd = enabled ? WATCHDOG : 0;
        return encode(address, cmd, presetIndex & 0xFF, resetTime & 0xFF, 0);
    }

    /**
     * 辅助开关（雨刷 / 补光灯）。
     *
     * <p>标准未为辅助设备定义独立指令码，主流实现约定：
     * 字节4 置 {@code 0x40}（进入扩展模式），字节5 为辅助开关编号，字节6 为开关值。
     *
     * @param index 辅助开关编号（0 雨刷 / 1 补光灯 …）
     * @param on    开 / 关
     */
    public static String auxiliary(int address, int index, boolean on) {
        return encode(address, PRESET_MODE, index & 0xFF, on ? 1 : 0, 0);
    }

    /**
     * 拉框缩放。
     *
     * <p>标准 A.3 的 {@code DragZoomIn} / {@code DragZoomOut}：指令码 {@code 0x81}/{@code 0x82}，
     * 后接 6 个坐标字节（长度、宽度、中心 X/Y、框长、框宽），报文长 11 字节。
     */
    public static String dragZoom(boolean zoomIn, int length, int width,
                                  int midpointX, int midpointY, int lengthX, int lengthY) {
        int cmd = zoomIn ? 0x81 : 0x82;
        int[] payload = {length, width, midpointX, midpointY, lengthX, lengthY};
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02X", HEADER));
        sb.append("0F");
        sb.append("01");
        sb.append(String.format("%02X", cmd));
        int sum = HEADER + 0x0F + 0x01 + cmd;
        for (int v : payload) {
            int b = v & 0xFF;
            sb.append(String.format("%02X", b));
            sum += b;
        }
        sb.append(String.format("%02X", sum & 0xFF));
        return sb.toString();
    }
}
