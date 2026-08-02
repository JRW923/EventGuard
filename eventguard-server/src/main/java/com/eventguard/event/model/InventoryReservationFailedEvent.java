package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 库存预留失败事件：网关返回库存不足，状态不立即变更（订单仍 PAID），
 * 触发 R005 类规则 + Saga 补偿（标记缺货/退款）。
 */
public class InventoryReservationFailedEvent extends DomainEvent {
    private final String skuId;
    private final int quantity;
    private final String reason;

    public InventoryReservationFailedEvent(UUID orderId, int version, String skuId, int quantity,
                                           String reason, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.skuId = skuId;
        this.quantity = quantity;
        this.reason = reason;
    }

    public InventoryReservationFailedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                                           Map<String, String> metadata, String skuId, int quantity, String reason) {
        super(eventId, aggregateId, "InventoryReservationFailedEvent", version, occurredAt, metadata);
        this.skuId = skuId;
        this.quantity = quantity;
        this.reason = reason;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "skuId", skuId, "quantity", quantity, "reason", reason);
    }

    public String getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
}
