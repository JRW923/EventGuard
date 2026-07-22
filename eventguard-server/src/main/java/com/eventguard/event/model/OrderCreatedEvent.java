package com.eventguard.event.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OrderCreatedEvent extends DomainEvent {
    private final String userId;
    private final BigDecimal totalAmount;

    public OrderCreatedEvent(UUID orderId, int version, String userId, BigDecimal totalAmount, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.userId = userId;
        this.totalAmount = totalAmount;
    }

    @Override
    public Object getPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", getAggregateId());
        payload.put("userId", userId);
        payload.put("totalAmount", totalAmount);
        return payload;
    }

    public String getUserId() { return userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
}
