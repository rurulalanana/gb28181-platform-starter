package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大华录像计划时段解析测试。
 *
 * <p>这段解析直接决定界面显示「录像中 / 不录像」，解析错就会让用户看到与实际相反的状态，
 * 所以单独测。真实格式（真机实测）：
 * <pre>
 *   "6 00:00:00-23:59:59"   ← 类型 6，周六全天录像
 *   "0 00:00:00-23:59:59"   ← 类型 0 = 该时段不录像
 * </pre>
 */
class DahuaRecordPlanSlotTest {

    @Test
    @DisplayName("正常时段：类型非 0 即启用")
    void enabledSlot() {
        var slot = DahuaRecordPlanService.parseSlot("6 00:00:00-23:59:59");
        assertEquals(6, slot.dayType());
        assertEquals("00:00:00", slot.start());
        assertEquals("23:59:59", slot.end());
        assertTrue(slot.enabled());
    }

    @Test
    @DisplayName("类型 0 表示不录像")
    void disabledSlot() {
        var slot = DahuaRecordPlanService.parseSlot("0 00:00:00-23:59:59");
        assertEquals(0, slot.dayType());
        assertFalse(slot.enabled());
        // 时间范围仍要解析出来，界面要显示「原本配了哪个时段但没启用」
        assertEquals("00:00:00", slot.start());
        assertEquals("23:59:59", slot.end());
    }

    @Test
    @DisplayName("多个空格 / 前后空白不影响解析")
    void toleratesWhitespace() {
        var slot = DahuaRecordPlanService.parseSlot("  3   08:30:00-18:00:00  ");
        assertEquals(3, slot.dayType());
        assertEquals("08:30:00", slot.start());
        assertEquals("18:00:00", slot.end());
        assertTrue(slot.enabled());
    }

    @Test
    @DisplayName("格式非法时按「不录像」处理，不抛异常")
    void malformedTreatedAsDisabled() {
        // 一个坏时段不该让整张计划打不开
        assertEquals(0, DahuaRecordPlanService.parseSlot("garbage").dayType());
        assertFalse(DahuaRecordPlanService.parseSlot("garbage").enabled());
        assertEquals(0, DahuaRecordPlanService.parseSlot("x 00:00:00-23:59:59").dayType());
        assertFalse(DahuaRecordPlanService.parseSlot("x 00:00:00-23:59:59").enabled());
    }

    @Test
    @DisplayName("空字符串不崩，直接视为不录像")
    void blankSlot() {
        var slot = DahuaRecordPlanService.parseSlot("");
        assertEquals(0, slot.dayType());
        assertFalse(slot.enabled());
        assertEquals(0, DahuaRecordPlanService.parseSlot(null).dayType());
    }

    @Test
    @DisplayName("只有开始时间没有结束时间也不崩")
    void missingEndTime() {
        var slot = DahuaRecordPlanService.parseSlot("2 10:00:00");
        assertEquals(2, slot.dayType());
        assertEquals("10:00:00", slot.start());
        assertNull(slot.end());
        assertTrue(slot.enabled());
    }
}
