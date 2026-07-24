package com.eventguard.anomaly.model;

import com.eventguard.event.model.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 通用事件包装类：从 REST 请求 JSON 重建，用于规则引擎评估。
 * payload 以 Map 形式保存事件特有字段。
 */
public class SimpleEvent extends DomainEvent {

    private final Map<String, Object> payloadMap;

    public SimpleEvent(UUID eventId, UUID aggregateId, String eventType, int version,
                       Instant occurredAt, Map<String, String> metadata,
                       Map<String, Object> payloadMap) {
        super(eventId, aggregateId, eventType, version, occurredAt, metadata);
        this.payloadMap = payloadMap == null ? Map.of() : Map.copyOf(payloadMap);
    }

    @Override
    public Object getPayload() {
        return payloadMap;
    }

    public Map<String, Object> getPayloadMap() {
        return payloadMap;
    }

    /** 从 payload 中取 BigDecimal 字段 */
    public BigDecimal getBigDecimal(String key) {
        Object v = payloadMap.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }

    /** 从 payload 中取 int 字段 */
    public int getInt(String key) {
        Object v = payloadMap.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    /** 从 payload 中取 String 字段 */
    public String getString(String key) {
        Object v = payloadMap.get(key);
        return v == null ? null : v.toString();
    }
}
