package com.eventguard.query.model;

import java.math.BigDecimal;

/**
 * 订单统计聚合结果（按 status 分组）。
 */
public class OrderStats {

    private String status;
    private long orderCount;
    private BigDecimal totalAmount;

    public OrderStats() {}

    public OrderStats(String status, long orderCount, BigDecimal totalAmount) {
        this.status = status;
        this.orderCount = orderCount;
        this.totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getOrderCount() { return orderCount; }
    public void setOrderCount(long orderCount) { this.orderCount = orderCount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
}
