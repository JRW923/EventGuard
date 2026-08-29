package com.eventguard.event.store;

import com.eventguard.event.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 事件反序列化器：从 DB 行字段或 Kafka JSON 还原 DomainEvent 子类实例。
 * event_type 字段决定具体子类。
 *
 * ponytail: 契约容错上限——反序列化只保证“能还原成 DomainEvent”，不保证业务字段齐全。
 * 未知 event_type / 字段缺失或类型错 / 结构既非 envelope 也非展平，一律降级为 UnknownEvent 占位，
 * 由消费端统一忽略；不再抛异常，杜绝坏消息在 主topic↔DLT 间反复重放拖垮内存（历史 OOM 根因之一）。
 */
@Component
public class EventDeserializer {

    private static final Logger log = LoggerFactory.getLogger(EventDeserializer.class);

    private final ObjectMapper objectMapper;

    public EventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DomainEvent deserialize(UUID eventId, UUID aggregateId, String eventType, int version,
                                   Instant occurredAt, Map<String, String> metadata, String payloadJson) {
        try {
            JsonNode p = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson);
            return switch (eventType) {
                case "OrderCreatedEvent" -> new OrderCreatedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "userId"), decimalOrNull(p, "totalAmount"));
                case "PaymentCompletedEvent" -> new PaymentCompletedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "paymentId"));
                case "PaymentFailedEvent" -> new PaymentFailedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "reason"));
                case "PaymentRetriedEvent" -> new PaymentRetriedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        p.has("retryCount") ? intOrZero(p, "retryCount") : intOrZero(p, "attempt"));   // 兼容旧 attempt 字段
                case "PaymentRequestedEvent" -> new PaymentRequestedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        uuidOrNull(p, "commandId"));
                case "InventoryReservedEvent" -> new InventoryReservedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "skuId"), intOrZero(p, "quantity"));
                case "InventoryReservationFailedEvent" -> new InventoryReservationFailedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "skuId"), intOrZero(p, "quantity"), textOrNull(p, "reason"));
                case "OrderConfirmedEvent" -> new OrderConfirmedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "ShippedEvent" -> new ShippedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "trackingNo"));
                case "DeliveredEvent" -> new DeliveredEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderClosedEvent" -> new OrderClosedEvent(eventId, aggregateId, version, occurredAt, metadata);
                case "OrderCancelledEvent" -> new OrderCancelledEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "reason"));
                case "OrderRefundedEvent" -> new OrderRefundedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        decimalOrNull(p, "refundAmount"));
                case "OrderRefundRequestedEvent" -> new OrderRefundRequestedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        decimalOrNull(p, "refundAmount"));
                case "CompensationExecutedEvent" -> new CompensationExecutedEvent(eventId, aggregateId, version, occurredAt, metadata,
                        textOrNull(p, "actionType"),
                        hasParams(p) ? objectMapper.convertValue(p.get("params"), new TypeReference<Map<String, Object>>() {})
                                    : Map.of());
                default -> unknownEvent(eventId, aggregateId, eventType, version, occurredAt, metadata);
            };
        } catch (Exception e) {
            // 字段映射等仍意外失败 → 降级占位，不抛（截断原始 payload 便于排查）
            String preview = payloadJson == null ? "null" : payloadJson.substring(0, Math.min(payloadJson.length(), 2000));
            log.warn("[反序列化] 降级为 UnknownEvent, eventType={}, payload={}", eventType, preview);
            return unknownEvent(eventId, aggregateId, eventType, version, occurredAt, metadata);
        }
    }

    /**
     * 从 Kafka JSON 反序列化事件。
     * 兼容两种形态（不依赖 Debezium 的 ExtractNewRecordState 是否生效）：
     *  - envelope：{"schema":{...},"payload":{event_id,...}}（当前 Debezium Server 实际产出）
     *  - 展平：{event_id,...}（ExtractNewRecordState 生效时）
     * 结构既非 envelope 也非展平（缺 event_id/aggregate_id/event_type）→ 降级 UnknownEvent，不抛。
     */
    public DomainEvent deserializeFromKafka(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            // envelope 形态：事件字段在 root.payload 下
            JsonNode ev = (root.has("payload") && root.get("payload").has("event_id")) ? root.get("payload") : root;
            String eventIdStr = textOrNull(ev, "event_id");
            String aggregateIdStr = textOrNull(ev, "aggregate_id");
            String eventType = textOrNull(ev, "event_type");
            if (eventIdStr == null || aggregateIdStr == null || eventType == null) {
                // 结构无法识别 → 占位，原始消息截断入 metadata 便于排查
                String preview = json == null ? "null" : json.substring(0, Math.min(json.length(), 2000));
                log.warn("[反序列化] 结构无法识别，降级为 UnknownEvent, raw={}", preview);
                return unknownEvent(UUID.randomUUID(), null, eventType, 0, Instant.now(), Map.of("raw", preview));
            }
            UUID eventId = UUID.fromString(eventIdStr);
            UUID aggregateId = UUID.fromString(aggregateIdStr);
            int version = intOrZero(ev, "event_version");
            Instant occurredAt = instantOrNow(ev, "created_at");
            Map<String, String> metadata = toTextMap(ev.get("metadata"));
            JsonNode payloadNode = ev.get("payload");
            String payloadJson = (payloadNode == null) ? "{}"
                    : (payloadNode.isTextual() ? payloadNode.asText() : payloadNode.toString());
            return deserialize(eventId, aggregateId, eventType, version, occurredAt, metadata, payloadJson);
        } catch (Exception e) {
            // ponytail: 结构解析意外失败（如 event_id 非法）→ 占位而非抛，断掉 DLT 循环
            String preview = json == null ? "null" : json.substring(0, Math.min(json.length(), 2000));
            log.warn("[反序列化] Kafka 消息解析失败，降级为 UnknownEvent, raw={}", preview, e);
            return unknownEvent(UUID.randomUUID(), null, null, 0, Instant.now(), Map.of("raw", preview));
        }
    }

    private static final UUID UNKNOWN_AGGREGATE = new UUID(0L, 0L);  // 占位，避免下游 hashCode() 对 null 抛 NPE

    private DomainEvent unknownEvent(UUID eventId, UUID aggregateId, String eventType, int version,
                                     Instant occurredAt, Map<String, String> metadata) {
        UUID agg = aggregateId == null ? UNKNOWN_AGGREGATE : aggregateId;
        return new UnknownEvent(eventId, agg, eventType, version, occurredAt, metadata);
    }

    private static boolean hasParams(JsonNode p) {
        JsonNode n = p.get("params");
        return n != null && !n.isNull();
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
            // 连序列化回退都失败（极罕见）→ 占位而非抛
            log.warn("[反序列化] 非字符串消息无法还原，降级为 UnknownEvent", e);
            return unknownEvent(UUID.randomUUID(), null, null, 0, Instant.now(), Map.of());
        }
    }

    // ---- 容错字段读取：缺失/类型错一律返回 null 或 0，不抛 ----

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static BigDecimal decimalOrNull(JsonNode n, String field) {
        String s = textOrNull(n, field);
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuidOrNull(JsonNode n, String field) {
        String s = textOrNull(n, field);
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int intOrZero(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? 0 : v.asInt();
    }

    private static Instant instantOrNow(JsonNode n, String field) {
        String s = textOrNull(n, field);
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
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
}
