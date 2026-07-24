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
                UUID.fromString(eventId),
                UUID.fromString(aggregateId),
                eventType,
                version,
                Instant.parse(occurredAt),
                metadata,
                payload
        );
    }
}
