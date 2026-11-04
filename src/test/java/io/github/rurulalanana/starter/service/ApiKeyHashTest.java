package io.github.rurulalanana.starter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 密钥的存储形态。
 *
 * <p>几个必须钉住的性质：
 * <ul>
 *   <li>同样的明文必须算出同样的哈希 —— 登录时靠它做等值查询；</li>
 *   <li>哈希必须是 64 位小写十六进制 —— 列是 {@code VARCHAR(64)}，超了写不进去；</li>
 *   <li>不同明文不能撞 —— 相接的相似串是最容易暴露出「取了几位就截断」的用例。</li>
 * </ul>
 */
class ApiKeyHashTest {

    @Test
    @DisplayName("哈希稳定：同一明文两次结果相同，且是 64 位小写十六进制")
    void stableAndWellFormed() {
        String a = UserService.hashApiKey("abc-123-XYZ");
        String b = UserService.hashApiKey("abc-123-XYZ");
        assertEquals(a, b, "哈希必须确定，否则登录时按哈希查不到用户");
        assertEquals(64, a.length(), "列是 VARCHAR(64)，长度必须刚好 64");
        assertTrue(a.matches("[0-9a-f]{64}"), "必须小写十六进制，实际：" + a);
    }

    @Test
    @DisplayName("大小写敏感：密钥是随机串，不做模糊匹配")
    void caseSensitive() {
        assertNotEquals(UserService.hashApiKey("AbCdEf"), UserService.hashApiKey("abcdef"));
    }

    @Test
    @DisplayName("相邻/前缀不同的串哈希完全不同（防截断或前缀比较）")
    void noCollisionOnSimilarInputs() {
        Set<String> seen = new HashSet<>();
        String[] samples = {"key-000001", "key-000002", "key-000001 ", " key-000001", "key-0000011"};
        for (String s : samples) {
            assertTrue(seen.add(UserService.hashApiKey(s)), "重复哈希：" + s);
        }
        assertEquals(samples.length, seen.size());
    }

    @Test
    @DisplayName("32 字节 base64url 密钥长度符合预期，且每次生成都不同")
    void generatedKeysAreDistinct() {
        Set<String> keys = new HashSet<>();
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (int i = 0; i < 200; i++) {
            byte[] raw = new byte[32];
            rnd.nextBytes(raw);
            // 与 UserService.generateApiKey 里的编码方式保持一致
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            assertTrue(keys.add(key));
            // 43 字符：32 字节 base64 不带填充
            assertEquals(43, key.length());
        }
    }
}
