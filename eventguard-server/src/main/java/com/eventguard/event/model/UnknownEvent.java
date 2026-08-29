package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 占位事件：契约不兼容（未知 event_type / 字段缺失 / 结构无法识别）时的降级产物。
 * 消费端（投影、 saga、异常告警）统一忽略，既不抛异常进 DLT 循环，也不污染读模型。
 * 原始结构存于 metadata，便于事后排查契约漂移。
 */
public class UnknownEvent extends DomainEvent {

    public UnknownEvent(UUID eventId, UUID aggregateId, String eventType, int version,
                        Instant occurredAt, Map<String, String> metadata) {
        super(eventId, aggregateId, eventType, version, occurredAt, metadata);
    }

    @Override
    public Object getPayload() {
        return Map.of("eventType", getEventType());
    }
}
