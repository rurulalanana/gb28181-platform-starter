package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制应答等待（A.2.6.2）。
 *
 * <p>重点验证「SN / 通道必须都对上」：同通道连续操作会各带 SN，
 * 只看通道会把上一条的应答填给这一条，现象是「按钮显示成功但画面没动」。
 */
class ControlAckServiceTest {

    private static final String CH = "34020000001310000001";

    @Test
    @DisplayName("应答到达后能取回结果")
    void ackIsReturned() {
        ControlAckService svc = new ControlAckService();
        CompletableFuture<ControlAckService.Ack> f = svc.expect(CH, 7);
        svc.onResponse(CH, 7, "OK");

        ControlAckService.Ack ack = svc.await(CH, 7, f, 1000);
        assertNotNull(ack);
        assertEquals(7, ack.sn());
        assertEquals("OK", ack.result());
        assertTrue(ack.ok());
    }

    @Test
    @DisplayName("设备不答时超时返回 null（正常路径，不是异常）")
    void timeoutReturnsNull() {
        ControlAckService svc = new ControlAckService();
        CompletableFuture<ControlAckService.Ack> f = svc.expect(CH, 8);
        assertNull(svc.await(CH, 8, f, 200));
    }

    @Test
    @DisplayName("SN 不匹配不算本次应答")
    void wrongSnDoesNotMatch() {
        ControlAckService svc = new ControlAckService();
        CompletableFuture<ControlAckService.Ack> f = svc.expect(CH, 100);
        // 同通道的上一条命令的应答（设备慢回）不能填给这一条
        svc.onResponse(CH, 99, "OK");

        assertNull(svc.await(CH, 100, f, 200));
        assertNotNull(svc.lastOf(CH, 99));
        assertNull(svc.lastOf(CH, 100));
    }

    @Test
    @DisplayName("通道不匹配不算本次应答")
    void wrongChannelDoesNotMatch() {
        ControlAckService svc = new ControlAckService();
        CompletableFuture<ControlAckService.Ack> f = svc.expect(CH, 11);
        svc.onResponse("34020000001310000002", 11, "OK");
        assertNull(svc.await(CH, 11, f, 200));
    }

    @Test
    @DisplayName("ERROR 判定为失败")
    void errorIsFailure() {
        ControlAckService svc = new ControlAckService();
        CompletableFuture<ControlAckService.Ack> f = svc.expect(CH, 12);
        svc.onResponse(CH, 12, "ERROR");

        ControlAckService.Ack ack = svc.await(CH, 12, f, 1000);
        assertNotNull(ack);
        assertFalse(ack.ok());
    }

    @Test
    @DisplayName("OK 大小写不敏感，缺失不算 OK")
    void resultParsing() {
        assertTrue(new ControlAckService.Ack(1, CH, "ok", 0).ok());
        assertFalse(new ControlAckService.Ack(1, CH, null, 0).ok());
    }
}
