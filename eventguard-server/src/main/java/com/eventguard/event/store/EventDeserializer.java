package com.eventguard.event.store;

import com.eventguard.event.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 事件反序列化器：从 DB 行字段或 Kafka JSON 还原 DomainEvent 子类实例。
 * event_type 字段决定具体子类。
 */
@Component
public class EventDeserializer {

    private final ObjectMapper objectMapper;

    public EventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DomainEvent deserialize(UUID eventId, UUID aggregateId, String eventType, int version,
                                   Instant occurredAt, Map<String, String> metadata, String payloadJson) {
        try {
            JsonNode p = objectMapper.readTree(payloadJson);
            return switch (eventType) {
                case "OrderCreatedEvent" -> new OrderCreatedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("userId").asText(), new BigDecimal(p.get("totalAmount").asText()));
                case "PaymentCompletedEvent" -> new PaymentCompletedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("paymentId").asText());
                case "PaymentFailedEvent" -> new PaymentFailedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("reason").asText());
                case "PaymentRetriedEvent" -> new PaymentRetriedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("retryCount").asInt());
                case "InventoryReservedEvent" -> new InventoryReservedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("skuId").asText(), p.get("quantity").asInt());
                case "OrderConfirmedEvent" -> new OrderConfirmedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "ShippedEvent" -> new ShippedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("trackingNo").asText());
                case "DeliveredEvent" -> new DeliveredEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderClosedEvent" -> new OrderClosedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderCancelledEvent" -> new OrderCancelledEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("reason").asText());
                case "OrderRefundedEvent" -> new OrderRefundedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        new BigDecimal(p.get("refundAmount").asText()));
                default -> throw new IllegalStateException("未知事件类型: " + eventType);
            };
        } catch (Exception e) {
            throw new IllegalStateException("反序列化事件失败: " + eventType, e);
        }
    }

    public DomainEvent deserializeFromKafka(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            UUID eventId = UUID.fromString(root.get("event_id").asText());
            UUID aggregateId = UUID.fromString(root.get("aggregate_id").asText());
            String eventType = root.get("event_type").asText();
            int version = root.get("event_version").asInt();
            Instant occurredAt = Instant.parse(root.get("created_at").asText());
            Map<String, String> metadata = objectMapper.convertValue(
                    root.get("metadata"), new TypeReference<Map<String, String>>() {});
            String payloadJson = root.get("payload").toString();
            return deserialize(eventId, aggregateId, eventType, version, occurredAt, metadata, payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 消息反序列化失败", e);
        }
    }
}
