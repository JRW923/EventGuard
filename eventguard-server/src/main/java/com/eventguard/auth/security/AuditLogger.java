package com.eventguard.auth.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 认证/用户管理审计：追加写 auth_audit_log，审计失败不阻断主流程（仅记日志）。 */
@Component
public class AuditLogger {

    private final JdbcTemplate jdbc;

    public AuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String username, String action, String detail, String ip) {
        try {
            jdbc.update("INSERT INTO auth_audit_log(username, action, detail, ip) VALUES (?,?,?,?)",
                    username, action, detail, ip);
        } catch (Exception e) {
            // 审计失败不阻断业务
        }
    }
}
