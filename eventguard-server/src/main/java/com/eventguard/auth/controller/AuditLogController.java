package com.eventguard.auth.controller;

import com.eventguard.auth.security.RequirePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;

/**
 * 审计日志查询（P0-8）：读取 auth_audit_log，供管理员审计「谁做了什么」。
 * 需 user:manage 权限（管理员可见；operator/viewer 无此权限不可见）。
 */
@RestController
@RequestMapping("/audit-logs")
@RequirePermission("user:manage")
public class AuditLogController {

    private final JdbcTemplate jdbc;

    public AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditLogView(long id, String username, String action, String detail, String ip, String createdAt) {}

    @GetMapping
    public List<AuditLogView> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size,
                                   @RequestParam(required = false) String username) {
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;
        if (username != null && !username.isBlank()) {
            return jdbc.query(
                    "SELECT id, username, action, detail, ip, created_at FROM auth_audit_log " +
                            "WHERE username = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    (rs, i) -> toView(rs.getLong("id"), rs.getString("username"), rs.getString("action"),
                            rs.getString("detail"), rs.getString("ip"), rs.getTimestamp("created_at")),
                    username, limit, offset);
        }
        return jdbc.query(
                "SELECT id, username, action, detail, ip, created_at FROM auth_audit_log " +
                        "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> toView(rs.getLong("id"), rs.getString("username"), rs.getString("action"),
                        rs.getString("detail"), rs.getString("ip"), rs.getTimestamp("created_at")),
                limit, offset);
    }

    private AuditLogView toView(long id, String username, String action, String detail, String ip, Timestamp createdAt) {
        return new AuditLogView(id, username, action, detail, ip, createdAt != null ? createdAt.toInstant().toString() : null);
    }
}
