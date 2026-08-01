package com.eventguard.auth.dto;

import com.eventguard.auth.model.AppUser;

import java.util.List;

/** 用户视图（不含密码哈希），用于登录响应 / /auth/me / 用户管理列表。 */
public record UserView(Long id, String username, String displayName, boolean enabled,
                       boolean mustChangePassword, List<String> roles, List<String> permissions) {

    public static UserView from(AppUser u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.isEnabled(),
                u.isMustChangePassword(), u.getRoles(), u.getPermissions());
    }
}
