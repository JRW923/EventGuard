package com.eventguard.query.service;

import com.eventguard.query.model.OrderStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单统计聚合服务（GET /orders/stats）。
 *
 * MVP：模板 SQL，按 status 分组 + 时间窗过滤，AI 服务不直连 DB。
 */
@Service
public class OrderStatsService {

    private final JdbcTemplate jdbc;

    public OrderStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按状态分组统计订单数量与金额。
     *
     * @param status 状态过滤（null 表示全状态聚合）
     * @param from   时间窗起点（含），null 表示不限制
     * @param to     时间窗终点（含），null 表示不限制
     */
    public List<OrderStats> getStats(String status, Instant from, Instant to) {
        RowMapper<OrderStats> mapper = (rs, rowNum) -> new OrderStats(
                rs.getString("status"),
                rs.getLong("order_count"),
                rs.getBigDecimal("total_amount"));

        StringBuilder sql = new StringBuilder(
                "SELECT status, count(*) AS order_count, COALESCE(sum(total_amount), 0) AS total_amount " +
                        "FROM order_view");
        List<Object> args = new ArrayList<>();

        boolean hasWhere = false;
        if (status != null && !status.isBlank()) {
            sql.append(hasWhere ? " AND" : " WHERE").append(" status = ?");
            args.add(status);
            hasWhere = true;
        }
        if (from != null) {
            sql.append(hasWhere ? " AND" : " WHERE").append(" updated_at >= ?");
            args.add(from);
            hasWhere = true;
        }
        if (to != null) {
            sql.append(hasWhere ? " AND" : " WHERE").append(" updated_at <= ?");
            args.add(to);
            hasWhere = true;
        }

        sql.append(" GROUP BY status");
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }
}
