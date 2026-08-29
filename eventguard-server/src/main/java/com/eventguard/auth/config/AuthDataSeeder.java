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
 * BCrypt 哈希在运行时生成（避免在 SQL 中写死哈希）。
 * 演示账号（admin/operator/viewer）每次启动都会重置为配置的默认密码，且不要求首次登录强制改密；
 * 这样多人/多轮体验时，展示页上的默认密码始终可用。
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
        // 演示账号幂等重置：确保默认密码/启用状态在，展示页默认密码始终可用。
        // ponytail: 仅当密码【确实变化】时才递增 token_version 令旧 JWT 失效；否则每次启动都会
        // 踢掉所有在线会话——WS 握手不校验 tv 但 REST 校验，进异常看板补拉 /alerts/recent 会 401 被弹回登录页。
        Long existingId = jdbc.queryForObject("SELECT id FROM auth_user WHERE username = ?", Long.class, username);
        if (existingId == null) {
            jdbc.update("INSERT INTO auth_user(username, password_hash, display_name, enabled, must_change_password) "
                            + "VALUES (?,?,?,TRUE,FALSE)",
                    username, encoder.encode(password), displayName);
        } else {
            String storedHash = jdbc.queryForObject(
                    "SELECT password_hash FROM auth_user WHERE username = ?", String.class, username);
            // BCrypt 每次 encode 都换盐，哈希必不同，故用 matches 判等而非比对哈希串
            boolean passwordChanged = storedHash == null || !encoder.matches(password, storedHash);
            if (passwordChanged) {
                jdbc.update("UPDATE auth_user SET password_hash = ?, display_name = ?, enabled = TRUE, "
                                + "must_change_password = FALSE, token_version = token_version + 1, updated_at = now() "
                                + "WHERE username = ?",
                        encoder.encode(password), displayName, username);
            } else {
                // 密码未变：仅校正启用状态/展示名，不动 token_version，保留既有会话
                jdbc.update("UPDATE auth_user SET display_name = ?, enabled = TRUE, "
                                + "must_change_password = FALSE, updated_at = now() WHERE username = ?",
                        displayName, username);
            }
        }
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
