package com.eventguard.auth.controller;

import com.eventguard.auth.dto.LoginResponse;
import com.eventguard.auth.dto.UserView;
import com.eventguard.auth.security.AuditLogger;
import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点。POST /auth/login 为公开端点（AuthFilter 放行）；其余需有效 JWT。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final AuditLogger audit;

    public AuthController(AuthService authService, AuditLogger audit) {
        this.authService = authService;
        this.audit = audit;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req, HttpServletRequest request) {
        return authService.login(req.username(), req.password(), request.getRemoteAddr());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthPrincipal p = AuthPrincipal.from(request);
        audit.log(p.getUsername(), "LOGOUT", null, request.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    /** P2-16 登出所有设备：递增 token_version，使本账号所有已签发 JWT 失效。 */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletRequest request) {
        AuthPrincipal p = AuthPrincipal.from(request);
        authService.logoutAll(p.getUserId(), request.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        return authService.me(AuthPrincipal.from(request).getUserId());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req,
                                               HttpServletRequest request) {
        AuthPrincipal p = AuthPrincipal.from(request);
        authService.changePassword(p.getUserId(), req.oldPassword(), req.newPassword(),
                request.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    public record LoginRequest(String username, String password) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
}
