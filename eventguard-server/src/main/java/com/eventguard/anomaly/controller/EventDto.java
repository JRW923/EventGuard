package com.eventguard.anomaly.controller;

import com.eventguard.anomaly.model.SimpleEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** REST 请求 DTO：接收 AI 服务发来的事件 JSON */
public record EventDto(
        String eventId,
        String aggregateId,
        String eventType,
        int version,
        String occurredAt,
        Map<String, String> metadata,
        Map<String, Object> payload
) {
    public SimpleEvent toSimpleEvent() {
        return new SimpleEvent(
                parseUuid(eventId, "eventId"),
                parseUuid(aggregateId, "aggregateId"),
                eventType,
                version,
                parseInstant(occurredAt),
                metadata,
                payload
        );
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字段 " + field + " 不能为空");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("字段 " + field + " 不是合法 UUID: " + value);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字段 occurredAt 不能为空");
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("字段 occurredAt 不是合法时间: " + value);
        }
    }
}
