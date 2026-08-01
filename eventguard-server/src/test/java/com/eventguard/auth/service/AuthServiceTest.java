package com.eventguard.auth.service;

import com.eventguard.auth.dto.LoginResponse;
import com.eventguard.auth.model.AppUser;
import com.eventguard.auth.repository.UserRepository;
import com.eventguard.auth.security.AuditLogger;
import com.eventguard.auth.security.JwtService;
import com.eventguard.auth.security.LoginAttemptGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AuthService：登录成功/失败/锁定/禁用/改密校验。 */
class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final JwtService jwt = new JwtService("0123456789abcdef0123456789abcdef", 60);
    private final LoginAttemptGuard guard = new LoginAttemptGuard();
    private final AuthService auth = new AuthService(users, jwt, guard, audit);

    private AppUser user(String username, String rawPassword, boolean enabled) {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setDisplayName("测试");
        u.setEnabled(enabled);
        u.setMustChangePassword(true);
        u.setRoles(List.of("ADMIN"));
        u.setPermissions(List.of("order:read"));
        return u;
    }

    @Test
    void login_success_returnsTokenAndUser() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user("admin", "pass12345", true)));
        LoginResponse resp = auth.login("admin", "pass12345", "127.0.0.1");
        assertNotNull(resp.token());
        assertEquals("admin", resp.user().username());
        assertEquals(true, resp.user().mustChangePassword());
    }

    @Test
    void login_wrongPassword_throws401() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user("admin", "right12345", true)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> auth.login("admin", "wrong12345", "127.0.0.1"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void login_unknownUser_throws401() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> auth.login("ghost", "whatever1", "127.0.0.1"));
    }

    @Test
    void login_locked_throws429() {
        for (int i = 0; i < 5; i++) {
            guard.onFailure("admin");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> auth.login("admin", "pass12345", "127.0.0.1"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void login_disabled_throws403() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user("admin", "pass12345", false)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> auth.login("admin", "pass12345", "127.0.0.1"));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void changePassword_wrongOld_throws400() {
        when(users.findById(1L)).thenReturn(Optional.of(user("admin", "old123456", true)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> auth.changePassword(1L, "bad123456", "new123456", "127.0.0.1"));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void changePassword_shortNew_throws400() {
        when(users.findById(1L)).thenReturn(Optional.of(user("admin", "old123456", true)));
        assertThrows(ResponseStatusException.class,
                () -> auth.changePassword(1L, "old123456", "short", "127.0.0.1"));
    }

    @Test
    void changePassword_success_updatesHash() {
        when(users.findById(1L)).thenReturn(Optional.of(user("admin", "old123456", true)));
        auth.changePassword(1L, "old123456", "new123456", "127.0.0.1");
        verify(users).updatePassword(org.mockito.ArgumentMatchers.eq(1L), anyString());
    }
}
