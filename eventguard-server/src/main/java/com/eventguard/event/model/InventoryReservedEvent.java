package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class InventoryReservedEvent extends DomainEvent {
    private final String skuId;
    private final int quantity;

    public InventoryReservedEvent(UUID orderId, int version, String skuId, int quantity, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.skuId = skuId;
        this.quantity = quantity;
    }

    public InventoryReservedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                                  Map<String, String> metadata, String skuId, int quantity) {
        super(eventId, aggregateId, "InventoryReservedEvent", version, occurredAt, metadata);
        this.skuId = skuId;
        this.quantity = quantity;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "skuId", skuId, "quantity", quantity);
    }

    public String getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
}
