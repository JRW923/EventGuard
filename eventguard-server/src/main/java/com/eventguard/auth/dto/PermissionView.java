package com.eventguard.auth.dto;

import com.eventguard.auth.model.Permission;

/** 权限目录视图，供角色编辑页下拉。 */
public record PermissionView(Long id, String code, String description) {

    public static PermissionView from(Permission p) {
        return new PermissionView(p.getId(), p.getCode(), p.getDescription());
    }
}
