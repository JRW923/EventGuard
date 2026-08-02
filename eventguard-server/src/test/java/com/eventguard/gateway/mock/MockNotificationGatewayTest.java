package com.eventguard.gateway.mock;

import com.eventguard.gateway.NotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mock 通知网关单测：成功写 notification_log；DB 异常返回失败。 */
class MockNotificationGatewayTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockNotificationGateway gateway =
            new MockNotificationGateway(jdbc, new ObjectMapper());

    @Test
    void send_success_writes_notification_log() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        var r = gateway.send(new NotificationGateway.NotificationMessage(
                "DELAY", "user-1", UUID.randomUUID(), Map.of("order", "o1")));
        assertThat(r.success()).isTrue();
        assertThat(r.channel()).isEqualTo("mock");
        verify(jdbc).update(eq("INSERT INTO notification_log (id, aggregate_id, notification_type, recipient, channel, status, payload) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_db_error_returns_failure() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        var r = gateway.send(new NotificationGateway.NotificationMessage(
                "DELAY", "user-1", UUID.randomUUID(), Map.of()));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("db down");
    }
}
