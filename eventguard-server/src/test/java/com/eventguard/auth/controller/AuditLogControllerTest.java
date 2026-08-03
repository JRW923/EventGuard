package com.eventguard.auth.controller;

import com.eventguard.auth.security.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 审计日志查询端点单测。 */
class AuditLogControllerTest {

    @Test
    void list_returns_audit_rows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 无 username 过滤分支：返回一条记录（query(String, RowMapper, Object...) 重载）
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<?>>any(),
                any(Object[].class)))
                .thenAnswer(inv -> {
                    org.springframework.jdbc.core.RowMapper<?> rm = inv.getArgument(1);
                    // 模拟 ResultSet 行
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true).thenReturn(false);
                    when(rs.getLong("id")).thenReturn(1L);
                    when(rs.getString("username")).thenReturn("admin");
                    when(rs.getString("action")).thenReturn("LOGIN_OK");
                    when(rs.getString("detail")).thenReturn(null);
                    when(rs.getString("ip")).thenReturn("127.0.0.1");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-08-03T00:00:00Z")));
                    return List.of(rm.mapRow(rs, 0));
                });

        AuditLogController controller = new AuditLogController(jdbc);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].action").value("LOGIN_OK"));
    }
}
