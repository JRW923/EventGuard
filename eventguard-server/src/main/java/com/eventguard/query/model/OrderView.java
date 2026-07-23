package com.eventguard.query.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class OrderView {
    private UUID orderId;
    private String status;
    private BigDecimal totalAmount;
    private Instant paymentTime;
    private Instant shippingTime;
    private int version;
    private Instant updatedAt;

    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Instant getPaymentTime() { return paymentTime; }
    public void setPaymentTime(Instant paymentTime) { this.paymentTime = paymentTime; }
    public Instant getShippingTime() { return shippingTime; }
    public void setShippingTime(Instant shippingTime) { this.shippingTime = shippingTime; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
