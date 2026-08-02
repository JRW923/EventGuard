package com.eventguard.gateway.mock;

import com.eventguard.gateway.NotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mock 通知网关：写 notification_log 留痕（可追溯，AI 根因分析可查），并打日志。
 * 不真实发送；真实 Provider（企业微信 webhook / SMTP）由 D 步按 EG_NOTIFY_PROVIDER 切换。
 */
@Component
public class MockNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationGateway.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MockNotificationGateway(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public SendResult send(NotificationMessage msg) {
        try {
            String payloadJson = msg.payload() == null ? "{}" : objectMapper.writeValueAsString(msg.payload());
            jdbc.update(
                    "INSERT INTO notification_log (id, aggregate_id, notification_type, recipient, channel, status, payload) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), msg.orderId(), msg.type(), msg.recipient(), "mock",
                    "SENT", payloadJson);
            log.info("[通知] 已记录(type={}, order={}, recipient={})", msg.type(), msg.orderId(), msg.recipient());
            return new SendResult(true, "mock", null);
        } catch (Exception e) {
            log.warn("[通知] 记录失败: {}", e.getMessage());
            return new SendResult(false, "mock", e.getMessage());
        }
    }
}
