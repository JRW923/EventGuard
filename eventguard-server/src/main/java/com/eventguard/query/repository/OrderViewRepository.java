package com.eventguard.query.repository;

import com.eventguard.query.model.OrderView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderViewRepository {

    private final JdbcTemplate jdbc;

    // ponytail: 复用同一 ObjectMapper，避免 RowMapper 每次 mapRow 新建对象
    private static final com.fasterxml.jackson.databind.ObjectMapper EVENT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OrderViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderView> findById(UUID orderId) {
        RowMapper<OrderView> mapper = (rs, rowNum) -> {
            OrderView v = new OrderView();
            v.setOrderId(rs.getObject("order_id", UUID.class));
            v.setStatus(rs.getString("status"));
            v.setTotalAmount(rs.getBigDecimal("total_amount"));
            Timestamp pt = rs.getTimestamp("payment_time");
            v.setPaymentTime(pt != null ? pt.toInstant() : null);
            Timestamp st = rs.getTimestamp("shipping_time");
            v.setShippingTime(st != null ? st.toInstant() : null);
            v.setVersion(rs.getInt("version"));
            Timestamp ut = rs.getTimestamp("updated_at");
            v.setUpdatedAt(ut != null ? ut.toInstant() : null);
            return v;
        };
        List<OrderView> list = jdbc.query(
                "SELECT order_id, status, total_amount, payment_time, shipping_time, version, updated_at " +
                        "FROM order_view WHERE order_id = ?",
                mapper, orderId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public com.eventguard.query.model.OrderListResponse list(String status, int page, int size) {
        int offset = page * size;
        RowMapper<com.eventguard.query.model.OrderListItem> mapper = (rs, rowNum) -> {
            com.eventguard.query.model.OrderListItem item = new com.eventguard.query.model.OrderListItem();
            item.setOrderId(rs.getObject("order_id", java.util.UUID.class));
            item.setStatus(rs.getString("status"));
            item.setTotalAmount(rs.getBigDecimal("total_amount"));
            item.setVersion(rs.getInt("version"));
            Timestamp ut = rs.getTimestamp("updated_at");
            item.setUpdatedAt(ut != null ? ut.toInstant() : null);
            return item;
        };

        List<com.eventguard.query.model.OrderListItem> orders;
        long total;
        if (status == null || status.isBlank()) {
            orders = jdbc.query(
                    "SELECT order_id, status, total_amount, version, updated_at " +
                            "FROM order_view ORDER BY updated_at DESC NULLS LAST LIMIT ? OFFSET ?",
                    mapper, size, offset);
            Long cnt = jdbc.queryForObject("SELECT count(*) FROM order_view", Long.class);
            total = cnt == null ? 0 : cnt;
        } else {
            orders = jdbc.query(
                    "SELECT order_id, status, total_amount, version, updated_at " +
                            "FROM order_view WHERE status = ? ORDER BY updated_at DESC NULLS LAST LIMIT ? OFFSET ?",
                    mapper, status, size, offset);
            Long cnt = jdbc.queryForObject(
                    "SELECT count(*) FROM order_view WHERE status = ?", Long.class, status);
            total = cnt == null ? 0 : cnt;
        }
        return new com.eventguard.query.model.OrderListResponse(orders, total, page, size);
    }

    public List<com.eventguard.query.model.EventDto> findEventsByAggregateId(java.util.UUID aggregateId) {
        RowMapper<com.eventguard.query.model.EventDto> mapper = (rs, rowNum) -> {
            com.eventguard.query.model.EventDto dto = new com.eventguard.query.model.EventDto();
            dto.setEventId(rs.getObject("event_id", java.util.UUID.class));
            dto.setAggregateId(rs.getObject("aggregate_id", java.util.UUID.class));
            dto.setEventType(rs.getString("event_type"));
            dto.setVersion(rs.getInt("event_version"));
            String payloadStr = rs.getString("payload");
            java.util.Map<String, Object> payload = null;
            if (payloadStr != null) {
                try {
                    // ponytail: 单条畸形 payload 不应拖垮整条事件时间线查询
                    payload = EVENT_MAPPER.convertValue(EVENT_MAPPER.readTree(payloadStr),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                } catch (Exception e) {
                    payload = null;
                }
            }
            dto.setPayload(payload);
            Timestamp ct = rs.getTimestamp("created_at");
            dto.setCreatedAt(ct != null ? ct.toInstant() : null);
            return dto;
        };
        return jdbc.query(
                "SELECT event_id, aggregate_id, event_type, event_version, payload, created_at " +
                        "FROM domain_events WHERE aggregate_id = ? ORDER BY event_version",
                mapper, aggregateId);
    }
}
