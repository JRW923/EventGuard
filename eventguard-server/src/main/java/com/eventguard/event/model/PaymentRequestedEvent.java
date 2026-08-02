package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 支付请求事件（意图）：标记支付已发起，状态不立即变更（仍 PENDING_PAYMENT）。
 * 真实支付结果由网关异步回调经 CompletePaymentCommand 落 PaymentCompletedEvent。
 */
public class PaymentRequestedEvent extends DomainEvent {
    private final UUID commandId;

    public PaymentRequestedEvent(UUID orderId, int version, UUID commandId, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.commandId = commandId;
    }

    public PaymentRequestedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                                 Map<String, String> metadata, UUID commandId) {
        super(eventId, aggregateId, "PaymentRequestedEvent", version, occurredAt, metadata);
        this.commandId = commandId;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "commandId", commandId != null ? commandId.toString() : null);
    }

    public UUID getCommandId() { return commandId; }
}
