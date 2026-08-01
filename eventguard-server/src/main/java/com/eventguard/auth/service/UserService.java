package com.eventguard.auth.service;

import com.eventguard.auth.dto.UserView;
import com.eventguard.auth.model.AppUser;
import com.eventguard.auth.repository.UserRepository;
import com.eventguard.auth.security.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 用户管理：列表/新建/更新/重置密码/删除。 */
@Service
public class UserService {

    private final UserRepository users;
    private final AuditLogger audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository users, AuditLogger audit) {
        this.users = users;
        this.audit = audit;
    }

    public List<UserView> list() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    public UserView create(String username, String password, String displayName,
                           boolean enabled, List<Long> roleIds, String operator) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能为空");
        }
        if (users.usernameExists(username.trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        AuthService.validatePassword(password);
        AppUser u = new AppUser();
        u.setUsername(username.trim());
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(displayName == null || displayName.isBlank() ? username.trim() : displayName.trim());
        u.setEnabled(enabled);
        u.setMustChangePassword(true);
        long id = users.insert(u, roleIds == null ? List.of() : roleIds);
        audit.log(operator, "USER_CREATE", "创建用户 " + username.trim(), null);
        return UserView.from(users.findById(id).orElseThrow());
    }

    public UserView update(long id, String displayName, boolean enabled, List<Long> roleIds, String operator) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (displayName == null || displayName.isBlank()) {
            displayName = user.getUsername();
        }
        users.updateProfile(id, displayName.trim(), enabled);
        users.replaceRoles(id, roleIds == null ? List.of() : roleIds);
        audit.log(operator, "USER_UPDATE", "更新用户 " + user.getUsername(), null);
        return UserView.from(users.findById(id).orElseThrow());
    }

    /** 重置密码：强制下次登录改密。 */
    public void resetPassword(long id, String newPassword, String operator) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        AuthService.validatePassword(newPassword);
        users.updatePassword(id, encoder.encode(newPassword));
        users.setMustChangePassword(id, true);
        audit.log(operator, "USER_RESET_PASSWORD", "重置用户 " + user.getUsername() + " 密码", null);
    }

    public void delete(long id, String operator) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (user.getUsername().equals(operator)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前登录账号");
        }
        users.delete(id);
        audit.log(operator, "USER_DELETE", "删除用户 " + user.getUsername(), null);
    }
}
