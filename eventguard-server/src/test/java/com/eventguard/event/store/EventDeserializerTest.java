package com.eventguard.event.store;

import com.eventguard.event.model.PaymentRetriedEvent;
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
}
