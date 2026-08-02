package com.eventguard.gateway.repository;

import com.eventguard.gateway.model.GatewayRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** gateway_request 表：网关出站请求 + 支付异步回调关联。 */
@Repository
public class GatewayRequestRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GatewayRequestRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(GatewayRequest req) {
        try {
            jdbc.update(
                    "INSERT INTO gateway_request (id, command_id, aggregate_id, gateway_type, request_type, " +
                            "provider, external_ref, status, request_payload, response_payload, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, now(), now())",
                    req.getId(), req.getCommandId(), req.getAggregateId(), req.getGatewayType(), req.getRequestType(),
                    req.getProvider(), req.getExternalRef(), req.getStatus().name(),
                    toJson(req.getRequestPayload()), toJson(req.getResponsePayload()));
        } catch (Exception e) {
            throw new IllegalStateException("保存 GatewayRequest 失败", e);
        }
    }

    public Optional<GatewayRequest> findByExternalRef(String externalRef) {
        List<GatewayRequest> rows = jdbc.query(
                "SELECT id, command_id, aggregate_id, gateway_type, request_type, provider, external_ref, " +
                        "status, request_payload, response_payload, created_at, updated_at " +
                        "FROM gateway_request WHERE external_ref = ? ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> row(rs.getString("id"), rs.getString("command_id"), rs.getString("aggregate_id"),
                        rs.getString("gateway_type"), rs.getString("request_type"), rs.getString("provider"),
                        rs.getString("external_ref"), rs.getString("status"),
                        rs.getString("request_payload"), rs.getString("response_payload"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                externalRef);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<GatewayRequest> findByCommandId(UUID commandId) {
        List<GatewayRequest> rows = jdbc.query(
                "SELECT id, command_id, aggregate_id, gateway_type, request_type, provider, external_ref, " +
                        "status, request_payload, response_payload, created_at, updated_at " +
                        "FROM gateway_request WHERE command_id = ? ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> row(rs.getString("id"), rs.getString("command_id"), rs.getString("aggregate_id"),
                        rs.getString("gateway_type"), rs.getString("request_type"), rs.getString("provider"),
                        rs.getString("external_ref"), rs.getString("status"),
                        rs.getString("request_payload"), rs.getString("response_payload"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                commandId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateStatus(String externalRef, GatewayRequest.Status status, Map<String, Object> responsePayload) {
        jdbc.update(
                "UPDATE gateway_request SET status = ?, response_payload = ?::jsonb, updated_at = now() WHERE external_ref = ?",
                status.name(), toJson(responsePayload), externalRef);
    }

    private GatewayRequest row(String id, String commandId, String aggregateId, String gatewayType,
                               String requestType, String provider, String externalRef, String status,
                               String requestPayload, String responsePayload, Instant createdAt, Instant updatedAt) {
        return new GatewayRequest(
                UUID.fromString(id), UUID.fromString(commandId), UUID.fromString(aggregateId),
                gatewayType, requestType, provider, externalRef,
                GatewayRequest.Status.valueOf(status),
                fromJson(requestPayload), fromJson(responsePayload), createdAt, updatedAt);
    }

    private String toJson(Object o) {
        try {
            return o == null ? "{}" : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
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
