package com.eventguard.anomaly.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** EventDto 容错：非法输入（null/非法 UUID、缺时间戳）应在解析阶段抛 IllegalArgumentException，
 *  由控制器转成 400，而非在 UUID.fromString(null) 处抛 NPE 污染错误监控。 */
class EventDtoTest {

    @Test
    void 合法输入可解析() {
        EventDto dto = new EventDto(
                "3f1d8c2a-0000-4000-8000-000000000001",
                "3f1d8c2a-0000-4000-8000-000000000002",
                "PaymentReceived", 1, "2026-08-29T00:00:00Z",
                java.util.Map.of(), java.util.Map.of("amount", 10));
        assertDoesNotThrow(dto::toSimpleEvent);
    }

    @Test
    void 缺eventId应抛IllegalArgumentException() {
        EventDto dto = new EventDto(null, "3f1d8c2a-0000-4000-8000-000000000002",
                "PaymentReceived", 1, "2026-08-29T00:00:00Z", null, null);
        assertThrows(IllegalArgumentException.class, dto::toSimpleEvent);
    }

    @Test
    void 非法UUID应抛IllegalArgumentException() {
        EventDto dto = new EventDto("not-a-uuid", "3f1d8c2a-0000-4000-8000-000000000002",
                "PaymentReceived", 1, "2026-08-29T00:00:00Z", null, null);
        assertThrows(IllegalArgumentException.class, dto::toSimpleEvent);
    }

    @Test
    void 缺occurredAt应抛IllegalArgumentException() {
        EventDto dto = new EventDto("3f1d8c2a-0000-4000-8000-000000000001",
                "3f1d8c2a-0000-4000-8000-000000000002", "PaymentReceived", 1, null, null, null);
        assertThrows(IllegalArgumentException.class, dto::toSimpleEvent);
    }
}
