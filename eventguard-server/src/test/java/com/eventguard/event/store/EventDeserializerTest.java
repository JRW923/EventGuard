package com.eventguard.event.store;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.PaymentRetriedEvent;
import com.eventguard.event.model.UnknownEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeserializerTest {

    @Test
    void acceptsLegacyAttemptFieldForPaymentRetryEvents() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String json = "{\"payload\":{\"event_id\":\"" + eventId +
                "\",\"aggregate_id\":\"" + aggregateId +
                "\",\"event_type\":\"PaymentRetriedEvent\",\"event_version\":2," +
                "\"payload\":\"{\\\"orderId\\\":\\\"" + aggregateId +
                "\\\",\\\"attempt\\\":4}\",\"metadata\":\"{}\",\"created_at\":\"2026-08-15T00:00:00Z\"}}";

        PaymentRetriedEvent event = (PaymentRetriedEvent) new EventDeserializer(new ObjectMapper())
                .deserializeFromKafka(json);

        assertThat(event.getRetryCount()).isEqualTo(4);
    }

    @Test
    void unknownEventTypeDowngradesToUnknownEventInsteadOfThrowing() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String json = "{\"payload\":{\"event_id\":\"" + eventId +
                "\",\"aggregate_id\":\"" + aggregateId +
                "\",\"event_type\":\"PaymentRetriedEventV2\",\"event_version\":1," +
                "\"metadata\":\"{}\",\"created_at\":\"2026-08-15T00:00:00Z\"}}";

        DomainEvent event = new EventDeserializer(new ObjectMapper()).deserializeFromKafka(json);

        assertThat(event).isInstanceOf(UnknownEvent.class);
        assertThat(event.getEventType()).isEqualTo("PaymentRetriedEventV2");
    }

    @Test
    void missingFieldsDowngradeToNullInsteadOfThrowing() {
        // OrderCreatedEvent 缺 totalAmount：旧实现会 BigDecimal("") 抛 NFE，新实现降级为 null
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String json = "{\"payload\":{\"event_id\":\"" + eventId +
                "\",\"aggregate_id\":\"" + aggregateId +
                "\",\"event_type\":\"OrderCreatedEvent\",\"event_version\":1," +
                "\"payload\":\"{}\",\"metadata\":\"{}\",\"created_at\":\"2026-08-15T00:00:00Z\"}}";

        DomainEvent event = new EventDeserializer(new ObjectMapper()).deserializeFromKafka(json);

        assertThat(event).isNotInstanceOf(UnknownEvent.class);
        assertThat(event.getEventType()).isEqualTo("OrderCreatedEvent");
    }

    @Test
    void unrecognizableStructureDowngradesToUnknownEventInsteadOfThrowing() {
        // 既不是 envelope 也不是展平，缺 event_id/aggregate_id/event_type
        String json = "{\"foo\":\"bar\"}";

        DomainEvent event = new EventDeserializer(new ObjectMapper()).deserializeFromKafka(json);

        assertThat(event).isInstanceOf(UnknownEvent.class);
    }
}

