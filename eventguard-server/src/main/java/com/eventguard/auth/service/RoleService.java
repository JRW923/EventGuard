package com.eventguard.auth.service;

import com.eventguard.auth.dto.PermissionView;
import com.eventguard.auth.dto.RoleView;
import com.eventguard.auth.repository.RoleRepository;
import com.eventguard.auth.security.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** 角色/权限管理：列表/新建/更新/删除/权限目录。 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final AuditLogger audit;

    public RoleService(RoleRepository roles, AuditLogger audit) {
        this.roles = roles;
        this.audit = audit;
    }

    public List<RoleView> list() {
        return roles.findAll().stream().map(RoleView::from).toList();
    }

    public RoleView create(String code, String name, String description,
                           List<String> permissionCodes, String operator) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码与名称不能为空");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (roles.roleCodeExists(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色编码已存在");
        }
        long id = roles.insert(normalized, name.trim(), description,
                roles.permissionIdsByCodes(permissionCodes == null ? List.of() : permissionCodes));
        audit.log(operator, "ROLE_CREATE", "创建角色 " + normalized, null);
        return RoleView.from(roles.findById(id).orElseThrow());
    }

    public RoleView update(long id, String name, String description,
                           List<String> permissionCodes, String operator) {
        RoleView existing = RoleView.from(roles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在")));
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色名称不能为空");
        }
        roles.update(id, name.trim(), description);
        roles.replacePermissions(id, roles.permissionIdsByCodes(permissionCodes == null ? List.of() : permissionCodes));
        audit.log(operator, "ROLE_UPDATE", "更新角色 " + existing.code(), null);
        return RoleView.from(roles.findById(id).orElseThrow());
    }

    public void delete(long id, String operator) {
        roles.delete(id);
        audit.log(operator, "ROLE_DELETE", "删除角色 id=" + id, null);
    }

    public List<PermissionView> permissions() {
        return roles.findAllPermissions().stream().map(PermissionView::from).toList();
    }
}
