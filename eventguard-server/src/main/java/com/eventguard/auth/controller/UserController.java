package com.eventguard.auth.controller;

import com.eventguard.auth.dto.UserView;
import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.security.RequirePermission;
import com.eventguard.auth.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户管理（user:manage）。 */
@RestController
@RequestMapping("/users")
@RequirePermission("user:manage")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserView> list() {
        return userService.list();
    }

    @PostMapping
    public UserView create(@RequestBody CreateUserRequest req, HttpServletRequest request) {
        return userService.create(req.username(), req.password(), req.displayName(),
                req.enabled() == null || req.enabled(), req.roleIds(), operator(request));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable long id, @RequestBody UpdateUserRequest req,
                           HttpServletRequest request) {
        return userService.update(id, req.displayName(), req.enabled() == null || req.enabled(),
                req.roleIds(), operator(request));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable long id,
                                              @RequestBody ResetPasswordRequest req,
                                              HttpServletRequest request) {
        userService.resetPassword(id, req.newPassword(), operator(request));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        userService.delete(id, operator(request));
        return ResponseEntity.ok().build();
    }

    private String operator(HttpServletRequest request) {
        return AuthPrincipal.from(request).getUsername();
    }

    public record CreateUserRequest(String username, String password, String displayName,
                                    Boolean enabled, List<Long> roleIds) {}
    public record UpdateUserRequest(String displayName, Boolean enabled, List<Long> roleIds) {}
    public record ResetPasswordRequest(String newPassword) {}
}
