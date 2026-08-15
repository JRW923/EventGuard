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
                        p.has("retryCount") ? p.get("retryCount").asInt() : p.path("attempt").asInt());
                case "PaymentRequestedEvent" -> new PaymentRequestedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.hasNonNull("commandId") ? UUID.fromString(p.get("commandId").asText()) : null);
                case "InventoryReservedEvent" -> new InventoryReservedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("skuId").asText(), p.get("quantity").asInt());
                case "InventoryReservationFailedEvent" -> new InventoryReservationFailedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("skuId").asText(), p.get("quantity").asInt(), p.get("reason").asText());
                case "OrderConfirmedEvent" -> new OrderConfirmedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "ShippedEvent" -> new ShippedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("trackingNo").asText());
                case "DeliveredEvent" -> new DeliveredEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderClosedEvent" -> new OrderClosedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderCancelledEvent" -> new OrderCancelledEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("reason").asText());
                case "OrderRefundedEvent" -> new OrderRefundedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        new BigDecimal(p.get("refundAmount").asText()));
                case "OrderRefundRequestedEvent" -> new OrderRefundRequestedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        new BigDecimal(p.get("refundAmount").asText()));
                case "CompensationExecutedEvent" -> new CompensationExecutedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.get("actionType").asText(),
                        objectMapper.convertValue(p.get("params"), new TypeReference<Map<String, Object>>() {}));
                default -> throw new IllegalStateException("未知事件类型: " + eventType);
            };
        } catch (IllegalStateException e) {
            throw e;  // 保留 "未知事件类型" 等业务异常的原消息
        } catch (Exception e) {
            throw new IllegalStateException("反序列化事件失败: " + eventType, e);
        }
    }

    /**
     * 从 Kafka JSON 反序列化事件。
     * 兼容两种形态（不依赖 Debezium 的 ExtractNewRecordState 是否生效）：
     *  - envelope：{"schema":{...},"payload":{event_id,...}}（当前 Debezium Server 实际产出）
     *  - 展平：{event_id,...}（ExtractNewRecordState 生效时）
     * envelope 中 payload / metadata 这俩 JSONB 列被序列化为 JSON 字符串（io.debezium.data.Json），
     * 展平后则是嵌套对象——两种都以文本优先解析。
     */
    public DomainEvent deserializeFromKafka(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            // envelope 形态：事件字段在 root.payload 下
            JsonNode ev = (root.has("payload") && root.get("payload").has("event_id")) ? root.get("payload") : root;
            UUID eventId = UUID.fromString(ev.get("event_id").asText());
            UUID aggregateId = UUID.fromString(ev.get("aggregate_id").asText());
            String eventType = ev.get("event_type").asText();
            int version = ev.get("event_version").asInt();
            Instant occurredAt = Instant.parse(ev.get("created_at").asText());
            Map<String, String> metadata = toTextMap(ev.get("metadata"));
            JsonNode payloadNode = ev.get("payload");
            String payloadJson = (payloadNode == null) ? "{}"
                    : (payloadNode.isTextual() ? payloadNode.asText() : payloadNode.toString());
            return deserialize(eventId, aggregateId, eventType, version, occurredAt, metadata, payloadJson);
        } catch (Exception e) {
            // ponytail: 打印原始消息便于定位 Debezium 输出结构与预期不符（截断避免刷屏）
            String preview = json == null ? "null" : json.substring(0, Math.min(json.length(), 2000));
            throw new IllegalStateException("Kafka 消息反序列化失败, 原始消息=" + preview, e);
        }
    }

    private Map<String, String> toTextMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        JsonNode obj = node.isTextual() ? tryParse(node.asText()) : node;
        if (obj == null || obj.isNull()) return Map.of();
        try {
            return objectMapper.convertValue(obj, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private JsonNode tryParse(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容 Spring {@code JsonDeserializer} 已把消息反序列化为 Map 的场景（见 application.yml 的
     * consumer.value-deserializer）。先把 Map 转回 JSON 字符串，再走上面的统一解析。
     * ponytail: 根因是全局 consumer 用了 JsonDeserializer，而投影侧假设拿到的是原始 JSON 字符串；
     * 这里用一次序列化回退兜底，避免每条事件被 ClassCastException 静默丢弃。
     */
    public DomainEvent deserializeFromKafka(Object value) {
        try {
            String json = (value instanceof String s) ? s : objectMapper.writeValueAsString(value);
            return deserializeFromKafka(json);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 消息反序列化失败", e);
        }
    }
}
