package com.eventguard.auth.repository;

import com.eventguard.auth.model.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/** 用户仓库：JdbcTemplate 风格，与现有 order_view 仓库保持一致。 */
@Repository
public class UserRepository {

    private static final RowMapper<AppUser> BASE_MAPPER = (rs, i) -> {
        AppUser u = new AppUser();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setDisplayName(rs.getString("display_name"));
        u.setEnabled(rs.getBoolean("enabled"));
        u.setMustChangePassword(rs.getBoolean("must_change_password"));
        return u;
    };

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, display_name, enabled, must_change_password "
                        + "FROM auth_user WHERE username = ?", BASE_MAPPER, username)
                .stream().findFirst().map(this::loadRolesAndPermissions);
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT id, username, password_hash, display_name, enabled, must_change_password "
                        + "FROM auth_user WHERE id = ?", BASE_MAPPER, id)
                .stream().findFirst().map(this::loadRolesAndPermissions);
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT id, username, password_hash, display_name, enabled, must_change_password "
                + "FROM auth_user ORDER BY id", BASE_MAPPER)
                .stream().peek(this::loadRolesAndPermissions).toList();
    }

    public boolean usernameExists(String username) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM auth_user WHERE username = ?", Integer.class, username);
        return n != null && n > 0;
    }

    public long insert(AppUser user, List<Long> roleIds) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO auth_user(username, password_hash, display_name, enabled, must_change_password) "
                            + "VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getDisplayName());
            ps.setBoolean(4, user.isEnabled());
            ps.setBoolean(5, user.isMustChangePassword());
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        replaceRoles(id, roleIds);
        return id;
    }

    public void updateProfile(long id, String displayName, boolean enabled) {
        jdbc.update("UPDATE auth_user SET display_name = ?, enabled = ?, updated_at = now() WHERE id = ?",
                displayName, enabled, id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE auth_user SET password_hash = ?, must_change_password = FALSE, updated_at = now() WHERE id = ?",
                passwordHash, id);
    }

    public void setMustChangePassword(long id, boolean flag) {
        jdbc.update("UPDATE auth_user SET must_change_password = ?, updated_at = now() WHERE id = ?", flag, id);
    }

    /** 重置角色集合：先删后插，事务由调用方/单条执行保证（单表小规模，无需显式事务）。 */
    public void replaceRoles(long userId, List<Long> roleIds) {
        jdbc.update("DELETE FROM auth_user_role WHERE user_id = ?", userId);
        for (Long roleId : roleIds) {
            jdbc.update("INSERT INTO auth_user_role(user_id, role_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    userId, roleId);
        }
    }

    public void delete(long id) {
        // auth_user_role 有 ON DELETE CASCADE，只需删主表
        jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
    }

    private AppUser loadRolesAndPermissions(AppUser u) {
        List<String> roles = jdbc.query("SELECT r.code FROM auth_role r "
                + "JOIN auth_user_role ur ON ur.role_id = r.id WHERE ur.user_id = ?",
                (rs, i) -> rs.getString("code"), u.getId());
        List<String> perms = jdbc.query("SELECT DISTINCT p.code FROM auth_permission p "
                        + "JOIN auth_role_permission rp ON rp.permission_id = p.id "
                        + "JOIN auth_user_role ur ON ur.role_id = rp.role_id WHERE ur.user_id = ?",
                (rs, i) -> rs.getString("code"), u.getId());
        u.setRoles(roles);
        u.setPermissions(perms);
        return u;
    }
}
