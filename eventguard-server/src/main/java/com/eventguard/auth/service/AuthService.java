package com.eventguard.auth.service;

import com.eventguard.auth.dto.LoginResponse;
import com.eventguard.auth.dto.UserView;
import com.eventguard.auth.model.AppUser;
import com.eventguard.auth.repository.UserRepository;
import com.eventguard.auth.security.AuditLogger;
import com.eventguard.auth.security.JwtService;
import com.eventguard.auth.security.LoginAttemptGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/** 认证服务：登录（含防爆破/审计）、当前用户、改密。 */
@Service
public class AuthService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final JwtService jwt;
    private final LoginAttemptGuard guard;
    private final AuditLogger audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository users, JwtService jwt, LoginAttemptGuard guard, AuditLogger audit) {
        this.users = users;
        this.jwt = jwt;
        this.guard = guard;
        this.audit = audit;
    }

    public LoginResponse login(String username, String password, String ip) {
        if (username == null || username.isBlank() || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名与密码不能为空");
        }
        if (guard.isLocked(username)) {
            long remain = guard.lockRemainingMillis(username) / 1000;
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "尝试次数过多，请 " + (remain / 60) + " 分钟后再试");
        }
        Optional<AppUser> found = users.findByUsername(username.trim());
        if (found.isEmpty() || !encoder.matches(password, found.get().getPasswordHash())) {
            guard.onFailure(username);
            audit.log(username, "LOGIN_FAILED", "用户名或密码错误", ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        AppUser user = found.get();
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用，请联系管理员");
        }
        guard.onSuccess(username);
        audit.log(username, "LOGIN_OK", null, ip);
        String token = jwt.issue(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoles(), user.getPermissions(), user.isMustChangePassword());
        return new LoginResponse(token, UserView.from(user));
    }

    /** /auth/me：重新从库加载以反映最新角色/权限（注：请求鉴权仍按 JWT claims 直到重新登录）。 */
    public UserView me(long userId) {
        return users.findById(userId).map(UserView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    public void changePassword(long userId, String oldPassword, String newPassword, String ip) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        validatePassword(newPassword);
        users.updatePassword(userId, encoder.encode(newPassword));
        audit.log(user.getUsername(), "PASSWORD_CHANGE", null, ip);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }
}
