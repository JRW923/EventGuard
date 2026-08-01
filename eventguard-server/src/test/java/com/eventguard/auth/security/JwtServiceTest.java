package com.eventguard.auth.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 签发/解析：roundtrip、篡改拒绝、过期拒绝。 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private final JwtService jwt = new JwtService(SECRET, 60);

    @Test
    void issueAndParse_roundtrip() {
        String token = jwt.issue(1L, "admin", "管理员",
                List.of("ADMIN"), List.of("order:read", "anomaly:view"), true);
        Claims c = jwt.parse(token);
        assertEquals("admin", c.get("username", String.class));
        assertEquals(1L, JwtService.uid(c));
        assertEquals(List.of("ADMIN"), JwtService.strings(c, "roles"));
        assertTrue(JwtService.strings(c, "permissions").contains("anomaly:view"));
        assertTrue(c.get("mcp", Boolean.class));
    }

    @Test
    void tamperedToken_rejected() {
        String token = jwt.issue(1L, "admin", null, List.of(), List.of(), false);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(Exception.class, () -> jwt.parse(tampered));
    }

    @Test
    void expiredToken_rejected() {
        JwtService shortLived = new JwtService(SECRET, 0); // 立即过期
        String token = shortLived.issue(1L, "admin", null, List.of(), List.of(), false);
        assertThrows(Exception.class, () -> jwt.parse(token));
    }
}
