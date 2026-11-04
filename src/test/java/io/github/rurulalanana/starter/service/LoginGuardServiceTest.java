package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.starter.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录防护的边界：失败计数 + 临时锁定。
 *
 * <p>这类逻辑改坏了编译期发现不了、线上才暴露：阈值写错等于爆破窗口被放大，
 * 清理计数写错等于锁不住人。API 密钥的校验在 {@code ApiKeyHashTest}。
 */
class LoginGuardServiceTest {

    private static LoginGuardService service() {
        // captchaApplication 在这几个用例里用不到（不碰验证码路径），传 null 即可
        LoginGuardService svc = new LoginGuardService(null);
        ReflectionTestUtils.setField(svc, "captchaEnabled", true);
        ReflectionTestUtils.setField(svc, "failWindowMinutes", 15);
        ReflectionTestUtils.setField(svc, "lockAfterFailures", 10);
        ReflectionTestUtils.setField(svc, "ipLockAfterFailures", 50);
        ReflectionTestUtils.setField(svc, "lockMinutes", 30);
        return svc;
    }

    // ==================== 失败计数与锁定 ====================

    @Test
    @DisplayName("账号失败 10 次后锁定，且未达标前不锁")
    void locksAfterThreshold() {
        LoginGuardService svc = service();
        for (int i = 0; i < 9; i++) {
            svc.onFailure("admin", "1.1.1.1");
        }
        assertDoesNotThrow(() -> svc.assertNotLocked("admin", "1.1.1.1"), "第 9 次还不该锁");

        svc.onFailure("admin", "1.1.1.1");
        BizException e = assertThrows(BizException.class,
                () -> svc.assertNotLocked("admin", "1.1.1.1"), "第 10 次必须锁住");
        assertTrue(e.getMessage().contains("临时锁定"), "实际消息：" + e.getMessage());
    }

    @Test
    @DisplayName("登录成功清掉自己的计数，账号不再被锁")
    void successClearsCounter() {
        LoginGuardService svc = service();
        for (int i = 0; i < 9; i++) {
            svc.onFailure("admin", "1.1.1.1");
        }
        svc.onSuccess("admin", "1.1.1.1");
        // 计数已清，再失败 9 次仍不该锁 —— 若没清，这里凑到 18 次必然锁
        for (int i = 0; i < 9; i++) {
            svc.onFailure("admin", "1.1.1.1");
        }
        assertDoesNotThrow(() -> svc.assertNotLocked("admin", "1.1.1.1"));
    }

    @Test
    @DisplayName("用户名大小写不敏感：Admin 与 admin 共用一个计数桶")
    void usernameIsCaseInsensitive() {
        LoginGuardService svc = service();
        for (int i = 0; i < 10; i++) {
            svc.onFailure(i % 2 == 0 ? "Admin" : "admin", "1.1.1.1");
        }
        assertThrows(BizException.class, () -> svc.assertNotLocked("ADMIN", "1.1.1.1"));
    }

    @Test
    @DisplayName("成功登录只清自己的计数，不会顺带解掉别人的锁定")
    void successDoesNotUnlockOthers() {
        LoginGuardService svc = service();
        for (int i = 0; i < 10; i++) {
            svc.onFailure("victim", "1.1.1.1");
        }
        // 攻击者用一个已知口令的账号登录成功
        svc.onSuccess("attacker", "1.1.1.1");
        // victim 仍应处于锁定 —— 但二者共用同一 IP 桶，IP 桶被清了，
        // 所以这里断言的是「账号桶仍锁」：换个 IP 访问 victim 也必须被拒
        assertThrows(BizException.class, () -> svc.assertNotLocked("victim", "2.2.2.2"));
    }
}
