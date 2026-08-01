package com.eventguard.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证种子数据（幂等）：权限目录 → 角色 → 角色-权限映射 → 默认用户 → 用户-角色映射。
 * BCrypt 哈希在运行时生成（避免在 SQL 中写死哈希）；默认账号首次登录强制改密。
 */
@Component
public class AuthDataSeeder implements ApplicationRunner {

    /** 权限目录（代码内定义，作为唯一事实来源）。 */
    static final Map<String, String> PERMISSIONS = new LinkedHashMap<>() {{
        put("order:read", "查看订单");
        put("order:create", "新建订单");
        put("order:write", "订单状态操作");
        put("anomaly:view", "异常看板与根因分析");
        put("ai:query", "自然语言查询");
        put("compensation:execute", "执行补偿");
        put("user:manage", "用户管理");
        put("role:manage", "角色与权限管理");
        put("anomaly:evaluate", "规则引擎评估（内部）");
    }};

    /** 角色 → 权限码。 */
    static final Map<String, List<String>> ROLES = new LinkedHashMap<>() {{
        put("ADMIN", List.copyOf(PERMISSIONS.keySet()));
        put("OPERATOR", List.of("order:read", "order:create", "order:write",
                "anomaly:view", "ai:query", "compensation:execute"));
        put("VIEWER", List.of("order:read", "anomaly:view", "ai:query"));
    }};

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String adminPassword;
    private final String operatorPassword;
    private final String viewerPassword;

    public AuthDataSeeder(JdbcTemplate jdbc,
                          @Value("${EG_ADMIN_PASSWORD:admin123456}") String adminPassword,
                          @Value("${EG_OPERATOR_PASSWORD:operator123456}") String operatorPassword,
                          @Value("${EG_VIEWER_PASSWORD:viewer123456}") String viewerPassword) {
        this.jdbc = jdbc;
        this.adminPassword = adminPassword;
        this.operatorPassword = operatorPassword;
        this.viewerPassword = viewerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedPermissions();
        seedRoles();
        seedUsers();
    }

    private void seedPermissions() {
        for (Map.Entry<String, String> e : PERMISSIONS.entrySet()) {
            jdbc.update("INSERT INTO auth_permission(code, description) VALUES (?,?) ON CONFLICT (code) DO NOTHING",
                    e.getKey(), e.getValue());
        }
    }

    private void seedRoles() {
        for (Map.Entry<String, List<String>> e : ROLES.entrySet()) {
            String code = e.getKey();
            jdbc.update("INSERT INTO auth_role(code, name, description) VALUES (?,?,?) ON CONFLICT (code) DO NOTHING",
                    code, roleName(code), roleDescription(code));
            Long roleId = jdbc.queryForObject("SELECT id FROM auth_role WHERE code = ?", Long.class, code);
            for (String permCode : e.getValue()) {
                Long permId = jdbc.queryForObject("SELECT id FROM auth_permission WHERE code = ?", Long.class, permCode);
                jdbc.update("INSERT INTO auth_role_permission(role_id, permission_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                        roleId, permId);
            }
        }
    }

    private void seedUsers() {
        seedUser("admin", adminPassword, "管理员", "ADMIN");
        seedUser("operator", operatorPassword, "运营", "OPERATOR");
        seedUser("viewer", viewerPassword, "只读访客", "VIEWER");
    }

    private void seedUser(String username, String password, String displayName, String roleCode) {
        jdbc.update("INSERT INTO auth_user(username, password_hash, display_name, enabled, must_change_password) "
                        + "VALUES (?,?,?,TRUE,TRUE) ON CONFLICT (username) DO NOTHING",
                username, encoder.encode(password), displayName);
        Long userId = jdbc.queryForObject("SELECT id FROM auth_user WHERE username = ?", Long.class, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM auth_role WHERE code = ?", Long.class, roleCode);
        jdbc.update("INSERT INTO auth_user_role(user_id, role_id) VALUES (?,?) ON CONFLICT DO NOTHING", userId, roleId);
    }

    private String roleName(String code) {
        return switch (code) {
            case "ADMIN" -> "管理员";
            case "OPERATOR" -> "运营";
            case "VIEWER" -> "只读访客";
            default -> code;
        };
    }

    private String roleDescription(String code) {
        return switch (code) {
            case "ADMIN" -> "全部权限";
            case "OPERATOR" -> "日常订单运营：下单、状态操作、异常处理、补偿";
            case "VIEWER" -> "只读：查看订单、异常看板、自然语言查询";
            default -> null;
        };
    }
}
