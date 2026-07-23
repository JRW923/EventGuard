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
}
