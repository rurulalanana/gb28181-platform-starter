package io.github.rurulalanana.starter.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 码流切换能力判定测试。
 *
 * <p>这段逻辑决定「通道预览弹窗里显不显示码流切换按钮」。
 * 判错的两种后果都不好：
 * <ul>
 *   <li>该显示却隐藏 → 弱网现场想切子码流省带宽，找不到入口</li>
 *   <li>不该显示却显示 → 用户切了，2016 版设备照样推主码流，
 *       画面没变化也没报错，只能怀疑平台坏了</li>
 * </ul>
 * 所以单独测。
 */
class StreamIndexMultiStreamTest {

    @Test
    @DisplayName("上报两路码流 → 支持切换")
    void twoStreams() {
        assertTrue(StreamIndex.supportsMultiStream("0,1"));
        assertTrue(StreamIndex.supportsMultiStream("1,0"));
    }

    @Test
    @DisplayName("上报三路码流 → 支持切换")
    void threeStreams() {
        assertTrue(StreamIndex.supportsMultiStream("0,1,2"));
    }

    @Test
    @DisplayName("只上报一路 → 不支持（单码流设备）")
    void singleStream() {
        assertFalse(StreamIndex.supportsMultiStream("0"));
        assertFalse(StreamIndex.supportsMultiStream("1"));
    }

    @Test
    @DisplayName("重复上报同一个编号 → 不支持（不是按逗号数判）")
    void duplicatedNumber() {
        // 实测有型号发 "0,0"；按逗号数会误判成两路
        assertFalse(StreamIndex.supportsMultiStream("0,0"));
        assertFalse(StreamIndex.supportsMultiStream("1,1,1"));
    }

    @Test
    @DisplayName("没上报 → 不支持（宁可少一个选项，也不给点了没反应的开关）")
    void notReported() {
        assertFalse(StreamIndex.supportsMultiStream(null));
        assertFalse(StreamIndex.supportsMultiStream(""));
        assertFalse(StreamIndex.supportsMultiStream("   "));
    }

    @Test
    @DisplayName("容忍各种分隔符与空白")
    void toleratesSeparators() {
        assertTrue(StreamIndex.supportsMultiStream("0, 1"));
        assertTrue(StreamIndex.supportsMultiStream("0，1"), "中文逗号");
        assertTrue(StreamIndex.supportsMultiStream("0;1"));
        assertTrue(StreamIndex.supportsMultiStream("0 1"));
        assertTrue(StreamIndex.supportsMultiStream(" 0 , 1 "));
    }

    @Test
    @DisplayName("空片段不算编号")
    void ignoresEmptyParts() {
        // "0,,1" 里空片段不该被当成第三个编号，但也不该影响「有两个不同编号」的判定
        assertTrue(StreamIndex.supportsMultiStream("0,,1"));
        assertFalse(StreamIndex.supportsMultiStream(",,"));
        assertFalse(StreamIndex.supportsMultiStream(","));
    }
}
