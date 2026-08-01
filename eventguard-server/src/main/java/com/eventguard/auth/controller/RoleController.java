package com.eventguard.auth.controller;

import com.eventguard.auth.dto.PermissionView;
import com.eventguard.auth.dto.RoleView;
import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.security.RequirePermission;
import com.eventguard.auth.service.RoleService;
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

/** 角色/权限管理（role:manage）。 */
@RestController
@RequestMapping("/roles")
@RequirePermission("role:manage")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public List<RoleView> list() {
        return roleService.list();
    }

    @PostMapping
    public RoleView create(@RequestBody CreateRoleRequest req, HttpServletRequest request) {
        return roleService.create(req.code(), req.name(), req.description(), req.permissions(),
                operator(request));
    }

    @PutMapping("/{id}")
    public RoleView update(@PathVariable long id, @RequestBody UpdateRoleRequest req,
                           HttpServletRequest request) {
        return roleService.update(id, req.name(), req.description(), req.permissions(), operator(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        roleService.delete(id, operator(request));
        return ResponseEntity.ok().build();
    }

    /** 权限目录（供角色编辑页勾选）。 */
    @GetMapping("/permissions")
    public List<PermissionView> permissions() {
        return roleService.permissions();
    }

    private String operator(HttpServletRequest request) {
        return AuthPrincipal.from(request).getUsername();
    }

    public record CreateRoleRequest(String code, String name, String description, List<String> permissions) {}
    public record UpdateRoleRequest(String name, String description, List<String> permissions) {}
}
