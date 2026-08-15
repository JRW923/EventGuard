package com.eventguard.compensation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** compensation_approval 表：审批流（对齐设计文档 7.4.4）。 */
@Repository
public class ApprovalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApprovalRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Approval(UUID approvalId, UUID sagaId, String actionType, UUID aggregateId,
                           Map<String, Object> params, String status, String requestedBy,
                           Instant requestedAt, Instant decidedAt, String decidedBy) {}

    public void insert(UUID approvalId, UUID sagaId, String actionType, UUID aggregateId,
                       Map<String, Object> params, String requestedBy) {
        try {
            String paramsJson = params == null ? "{}" : objectMapper.writeValueAsString(params);
            jdbc.update(
                    "INSERT INTO compensation_approval (approval_id, saga_id, action_type, aggregate_id, params, status, requested_by, requested_at) " +
                            "VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?)",
                    approvalId, sagaId, actionType, aggregateId, paramsJson, requestedBy, Timestamp.from(Instant.now()));
        } catch (Exception e) {
            throw new IllegalStateException("保存审批请求失败", e);
        }
    }

    public Optional<Approval> findByApprovalId(UUID approvalId) {
        List<Approval> rows = jdbc.query(
                "SELECT approval_id, saga_id, action_type, aggregate_id, params, status, requested_by, requested_at, decided_at, decided_by " +
                        "FROM compensation_approval WHERE approval_id = ?",
                (rs, i) -> new Approval(
                        UUID.fromString(rs.getString("approval_id")),
                        UUID.fromString(rs.getString("saga_id")),
                        rs.getString("action_type"),
                        UUID.fromString(rs.getString("aggregate_id")),
                        fromJson(rs.getString("params")),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant() : null,
                        rs.getString("decided_by")),
                approvalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Approval> findPending() {
        return jdbc.query(
                "SELECT approval_id, saga_id, action_type, aggregate_id, params, status, requested_by, requested_at, decided_at, decided_by " +
                        "FROM compensation_approval WHERE status = 'PENDING' ORDER BY requested_at",
                (rs, i) -> new Approval(
                        UUID.fromString(rs.getString("approval_id")),
                        UUID.fromString(rs.getString("saga_id")),
                        rs.getString("action_type"),
                        UUID.fromString(rs.getString("aggregate_id")),
                        fromJson(rs.getString("params")),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant() : null,
                        rs.getString("decided_by")));
    }

    public boolean decide(UUID approvalId, String status, String decidedBy) {
        return jdbc.update(
                "UPDATE compensation_approval SET status = ?, decided_by = ?, decided_at = ? " +
                        "WHERE approval_id = ? AND status = 'PENDING'",
                status, decidedBy, Timestamp.from(Instant.now()), approvalId) == 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
