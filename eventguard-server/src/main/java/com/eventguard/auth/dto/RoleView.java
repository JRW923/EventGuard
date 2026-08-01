package com.eventguard.auth.dto;

import com.eventguard.auth.model.Role;

import java.util.List;

/** 角色视图（含权限码），用于角色管理。 */
public record RoleView(Long id, String code, String name, String description, List<String> permissions) {

    public static RoleView from(Role r) {
        return new RoleView(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.getPermissions());
    }
}
