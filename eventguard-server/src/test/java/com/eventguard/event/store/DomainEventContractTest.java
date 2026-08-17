package com.eventguard.event.store;

import com.eventguard.event.model.OrderCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨栈事件契约（Java 侧）：OrderCreatedEvent 经 EventStore 写入 domain_events.payload 列的
 * 序列化结果，必须与 AI 消费端期望的字段对齐（orderId / userId / totalAmount）。
 * 与 AI 侧 test_event_contract.py 共同构成「Java 写 → AI 读」边界的契约门禁。
 *
 * 非集成测试：仅复刻 EventStore.toJson(getPayload()) 的序列化步骤，不依赖数据库。
 */
class DomainEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void order_created_payload_matches_cross_stack_contract() throws Exception {
        UUID orderId = UUID.randomUUID();
        var event = new OrderCreatedEvent(orderId, 1, "u1", new BigDecimal("99.00"), Map.of("userId", "u1"));

        // 事件类型即契约标识（AI 侧按 event_type 路由）
        assertThat(event.getEventType()).isEqualTo("OrderCreatedEvent");
        assertThat(event.getAggregateId()).isEqualTo(orderId);

        // payload 即 Java 写入 domain_events.payload 列的内容，需与 AI 消费端对齐
        String payloadJson = objectMapper.writeValueAsString(event.getPayload());
        JsonNode node = objectMapper.readTree(payloadJson);

        assertThat(node.has("orderId")).isTrue();
        assertThat(node.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(node.get("userId").asText()).isEqualTo("u1");
        assertThat(node.get("totalAmount").decimalValue()).isEqualByComparingTo("99.00");
    }
}
