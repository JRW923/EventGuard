package com.eventguard.query.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 订单列表项（GET /orders 返回）。
 */
public class OrderListItem {

    private UUID orderId;
    private String status;
    private BigDecimal totalAmount;
    private int version;
    private Instant updatedAt;

    public OrderListItem() {}

    public OrderListItem(UUID orderId, String status, BigDecimal totalAmount, int version, Instant updatedAt) {
        this.orderId = orderId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
