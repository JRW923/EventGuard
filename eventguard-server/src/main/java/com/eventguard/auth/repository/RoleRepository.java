package com.eventguard.auth.repository;

import com.eventguard.auth.model.Permission;
import com.eventguard.auth.model.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/** 角色/权限仓库。 */
@Repository
public class RoleRepository {

    private static final RowMapper<Role> ROLE_MAPPER = (rs, i) -> {
        Role r = new Role();
        r.setId(rs.getLong("id"));
        r.setCode(rs.getString("code"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        return r;
    };

    private static final RowMapper<Permission> PERM_MAPPER =
            (rs, i) -> new Permission(rs.getLong("id"), rs.getString("code"), rs.getString("description"));

    private final JdbcTemplate jdbc;

    public RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Role> findAll() {
        return jdbc.query("SELECT id, code, name, description FROM auth_role ORDER BY id", ROLE_MAPPER)
                .stream().peek(this::loadPermissions).toList();
    }

    public Optional<Role> findById(long id) {
        return jdbc.query("SELECT id, code, name, description FROM auth_role WHERE id = ?", ROLE_MAPPER, id)
                .stream().findFirst().map(this::loadPermissions);
    }

    public boolean roleCodeExists(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM auth_role WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public long insert(String code, String name, String description, List<Long> permissionIds) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO auth_role(code, name, description) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, description);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        replacePermissions(id, permissionIds);
        return id;
    }

    public void update(long id, String name, String description) {
        jdbc.update("UPDATE auth_role SET name = ?, description = ? WHERE id = ?", name, description, id);
    }

    public void replacePermissions(long roleId, List<Long> permissionIds) {
        jdbc.update("DELETE FROM auth_role_permission WHERE role_id = ?", roleId);
        for (Long pid : permissionIds) {
            jdbc.update("INSERT INTO auth_role_permission(role_id, permission_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    roleId, pid);
        }
    }

    public void delete(long id) {
        // auth_role_permission 有 ON DELETE CASCADE
        jdbc.update("DELETE FROM auth_role WHERE id = ?", id);
    }

    public List<Permission> findAllPermissions() {
        return jdbc.query("SELECT id, code, description FROM auth_permission ORDER BY id", PERM_MAPPER);
    }

    public List<Long> permissionIdsByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", codes.stream().map(c -> "?").toList());
        return jdbc.query("SELECT id FROM auth_permission WHERE code IN (" + in + ")",
                (rs, i) -> rs.getLong("id"), codes.toArray());
    }

    public List<String> permissionCodesByRoleId(long roleId) {
        return jdbc.query("SELECT p.code FROM auth_permission p "
                + "JOIN auth_role_permission rp ON rp.permission_id = p.id WHERE rp.role_id = ?",
                (rs, i) -> rs.getString("code"), roleId);
    }

    private Role loadPermissions(Role r) {
        r.setPermissions(permissionCodesByRoleId(r.getId()));
        return r;
    }
}
