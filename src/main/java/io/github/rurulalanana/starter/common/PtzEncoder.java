package io.github.rurulalanana.starter.common;

/**
 * GB/T 28181 PTZ 指令编码器（附录 A.3.1）。8 字节，编码为 16 个十六进制字符：
 * <pre>
 *   字节1  字节2        字节3    字节4    字节5   字节6   字节7        字节8
 *   0xA5   版本+校验位   地址低8  指令码   数据1   数据2   数据3+地址高4  校验码
 * </pre>
 * 字节2 = {@code (版本 << 4) | 校验位}，版本 0 时恒为 {@code 0x0F}；字节8 为前 7 字节算术和对 256 取模。
 * 字节5/6/7 高4位的含义随指令码变化。
 *
 * <p>指令码（已逐条比对 wvp-GB28181-pro 的编/解码）：
 * 0x00 停止；0x01/0x02 右/左；0x04/0x08 下/上（数据1水平速度、数据2垂直速度、数据3变倍速度）；
 * 0x10/0x20 变倍放大/缩小；0x41/0x42 聚焦远/近（数据1=聚焦速度）；0x44/0x48 光圈放大/缩小（数据2=光圈速度）；
 * 0x81/0x82/0x83 设置/调用/删除预置位（数据1=1，数据2=预置位编号）；
 * 0x84/0x85 加入/删除巡航点（数据1=组号，数据2=预置位）；0x86/0x87 巡航速度/停留时间；0x88 开始巡航；
 * 0x89 扫描（数据2=0 开始/1 左边界/2 右边界）；0x8A 扫描速度；0x8C/0x8D 辅助开关开/关（数据1=开关编号）。
 *
 * <p><b>坑</b>：早先按「Bit7=1 进入扩展模式」自行推导码值（{@code 0x40|0x20} 等）并把预置位编号写进字节5，
 * 那套编码标准里并不存在——真正的预置位指令是 {@code 0x81/0x82/0x83}，编号在字节6。
 * 设备收到 {@code 0x50} 只能忽略：SIP 回 200 OK，镜头不动，而报文完全合法规。
 *
 * <p><b>方向位与直觉相反</b>：低位是右/下，高位是左/上——{@code 右(0x01) < 左(0x02)}、{@code 下(0x04) < 上(0x08)}。
 * 标准约束：Bit2/Bit1、Bit4/Bit3、Bit6/Bit5 不可同时为 1。
 *
 * <p><b>看守位不走本编码器</b>：它有独立的 {@code <HomePosition>} 元素（{@code Enabled/ResetTime/PresetIndex}）。
 */
public final class PtzEncoder {

    /** 固定首字节。 */
    private static final int HEADER = 0xA5;

    // ---------- 方向与变倍（0x00~0x27） ----------
    /** 云台左（Bit2 = 0x02）—— 低位右/下、高位左/上，对应 wvp 的 {@code pan==0}。 */
    public static final int LEFT = 0x02;
    /** 云台右（Bit1 = 0x01）。对应 wvp 的 {@code pan==1}。 */
    public static final int RIGHT = 0x01;
    /** 云台上（Bit4 = 0x08）。对应 wvp 的 {@code tilt==0}。 */
    public static final int UP = 0x08;
    /** 云台下（Bit3 = 0x04）。对应 wvp 的 {@code tilt==1}。 */
    public static final int DOWN = 0x04;
    /** 变倍放大。 */
    public static final int ZOOM_IN = 0x10;
    /** 变倍缩小。 */
    public static final int ZOOM_OUT = 0x20;

    // ---------- 聚焦 / 光圈（0x40 基） ----------
    /** 聚焦远。数据1 = 聚焦速度。 */
    public static final int FOCUS_FAR = 0x41;
    /** 聚焦近。数据1 = 聚焦速度。 */
    public static final int FOCUS_NEAR = 0x42;
    /** 光圈放大（进光量增大）。数据2 = 光圈速度。 */
    public static final int IRIS_IN = 0x44;
    /** 光圈缩小。数据2 = 光圈速度。 */
    public static final int IRIS_OUT = 0x48;

    // ---------- 预置位（0x81~0x83） ----------
    /** 设置预置位。数据2 = 预置位编号。 */
    public static final int PRESET_SET = 0x81;
    /** 调用预置位。数据2 = 预置位编号。 */
    public static final int PRESET_CALL = 0x82;
    /** 删除预置位。数据2 = 预置位编号。 */
    public static final int PRESET_REMOVE = 0x83;

    // ---------- 巡航（0x84~0x88） ----------
    /** 加入巡航点。数据1 = 巡航组号，数据2 = 预置位编号。 */
    public static final int CRUISE_POINT_ADD = 0x84;
    /** 删除一个巡航点。数据1 = 巡航组号，数据2 = 预置位编号（0 = 删除整组）。 */
    public static final int CRUISE_POINT_DEL = 0x85;
    /** 设置巡航速度。数据1 = 组号，数据2/3 = 速度（1~4095，低 8 位 + 高 4 位）。 */
    public static final int CRUISE_SPEED = 0x86;
    /** 设置巡航停留时间。数据1 = 组号，数据2/3 = 秒数（1~4095）。 */
    public static final int CRUISE_TIME = 0x87;
    /** 开始巡航。数据1 = 巡航组号。 */
    public static final int CRUISE_START = 0x88;

    // ---------- 自动扫描（0x89~0x8A） ----------
    /** 开始扫描。数据1 = 扫描组号，数据2 = 0 开始 / 1 左边界 / 2 右边界。 */
    public static final int SCAN = 0x89;
    /** 设置扫描速度。数据1 = 组号，数据2/3 = 速度（1~4095）。 */
    public static final int SCAN_SPEED = 0x8A;

    // ---------- 辅助开关（0x8C~0x8D） ----------
    /** 辅助开关开。数据1 = 开关编号。 */
    public static final int AUX_ON = 0x8C;
    /** 辅助开关关。数据1 = 开关编号。 */
    public static final int AUX_OFF = 0x8D;

    private PtzEncoder() {
    }

    /**
     * 编码一条 PTZ 指令。address 取值 1~4095（直连设备通常填 1），返回 16 个十六进制字符。
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

    /** 方向 / 变倍控制（不带变倍速度）。command 由 {@link #LEFT} 等按位或组合；speed 0~255。 */
    public static String direction(int address, int command, int speed) {
        return direction(address, command, speed, 0);
    }

    /**
     * 方向 / 变倍控制（带变倍速度）。command 由 {@link #LEFT} 等按位或组合；speed 0~255；
     * zoomSpeed 0~15（写字节7 高 4 位），0 表示不变倍。
     */
    public static String direction(int address, int command, int speed, int zoomSpeed) {
        int sp = clamp(speed, 255);
        return encode(address, command, sp, sp, clamp(zoomSpeed, 15));
    }

    /** 云台停止（清空所有运动位）。也用于「停止巡航 / 停止扫描」：它们没有专属指令码。 */
    public static String stop(int address) {
        return encode(address, 0, 0, 0, 0);
    }

    /**
     * 聚焦 / 光圈。两者都不是变倍位：聚焦走 {@code 0x41/0x42}、光圈走 {@code 0x44/0x48}，
     * 与变倍 {@code 0x10/0x20} 无关。聚焦速度写字节5，光圈速度写字节6。
     */
    public static String fi(int address, int command, int focusSpeed, int irisSpeed) {
        // 两个速度各占一个字节，用不到的传 0 即可
        return encode(address, command, clamp(focusSpeed, 255), clamp(irisSpeed, 255), 0);
    }

    /**
     * 预置位操作，编号 1~255。<b>编号在字节6，不是字节5</b>；字节5 标准未定义，
     * 主流实现（wvp）固定填 1，这里保持一致——有的设备会校验它非 0。
     */
    public static String preset(int address, int operation, int presetIndex) {
        if (presetIndex < 1 || presetIndex > 255) {
            throw new IllegalArgumentException("预置位编号必须在 1~255 之间：" + presetIndex);
        }
        return encode(address, operation, 1, presetIndex, 0);
    }

    /** 加入 / 删除巡航点。groupId 1~255；presetIndex 为 0 表示删除整组。 */
    public static String cruisePoint(int address, boolean add, int groupId, int presetIndex) {
        return encode(address, add ? CRUISE_POINT_ADD : CRUISE_POINT_DEL,
                clamp(groupId, 255), clamp(presetIndex, 255), 0);
    }

    /**
     * 设置巡航速度 / 停留时间。值 1~4095 占两个字节（大端）：低 8 位进字节6，高 4 位进字节7 高半字节。
     * 早期把值整个塞进字节5，只能表达 0~255 且位置不对。
     */
    public static String cruiseParam(int address, int operation, int groupId, int value) {
        int v = Math.max(0, Math.min(4095, value));
        return encode(address, operation, clamp(groupId, 255), v & 0xFF, (v >> 8) & 0x0F);
    }

    /** 开始巡航。数据1 = 巡航组号。 */
    public static String cruiseStart(int address, int groupId) {
        return encode(address, CRUISE_START, clamp(groupId, 255), 0, 0);
    }

    /** 自动扫描。mode：0 开始扫描 / 1 设左边界 / 2 设右边界。 */
    public static String scan(int address, int groupId, int mode) {
        return encode(address, SCAN, clamp(groupId, 255), mode & 0x03, 0);
    }

    /** 设置自动扫描速度。数据1 = 组号，数据2/3 = 速度（1~4095）。 */
    public static String scanSpeed(int address, int groupId, int speed) {
        int v = Math.max(0, Math.min(4095, speed));
        return encode(address, SCAN_SPEED, clamp(groupId, 255), v & 0xFF, (v >> 8) & 0x0F);
    }

    /** 辅助开关（雨刷 / 补光灯）。<b>雨刷编号固定为 1</b>，其它编号由设备自定义。 */
    public static String auxiliary(int address, int index, boolean on) {
        return encode(address, on ? AUX_ON : AUX_OFF, clamp(index, 255), 0, 0);
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }
}
