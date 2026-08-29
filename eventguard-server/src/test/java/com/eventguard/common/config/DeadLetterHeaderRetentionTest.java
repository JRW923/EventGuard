package com.eventguard.common.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化 DLT 隔离机制依赖的前提：失败消息写回 DLT 时必须携带原始头，
 * DltReplayController 的 eg.dlt.replay.attempt 计数才能跨 DLT 往返累积、最终触发 .quarantine 隔离。
 * 实测 spring-kafka 3.2.2 的 DeadLetterPublishingRecoverer 默认即复制原始头（无需 addHeadersFunction）；
 * 若未来误加/误改 headersFunction 破坏该行为，本测试会失败。
 */
class DeadLetterHeaderRetentionTest {

    @Test
    void 写回DLT时保留原始头_隔离计数可累积() {
        MockProducer<String, String> mock = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        // 覆写 createProducer 注入 MockProducer（本项目无 mockito 依赖，不走 mock 框架）
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(new HashMap<>()) {
            @Override
            public Producer<String, String> createProducer() {
                return mock;
            }
        };
        KafkaTemplate<String, String> template = new KafkaTemplate<>(pf);

        // 与 KafkaConsumerConfig.kafkaErrorHandler 一致的配置
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));

        ConsumerRecord<String, String> in = new ConsumerRecord<>("domain-events", 0, 0L, "k", "{\"event_id\":\"x\"}");
        in.headers().add("eg.dlt.replay.attempt", "1".getBytes(StandardCharsets.UTF_8));

        recoverer.accept(in, new RuntimeException("boom"));

        assertEquals(1, mock.history().size(), "应写回 1 条 DLT 消息");
        ProducerRecord<String, String> dlt = mock.history().get(0);
        assertEquals("domain-events.DLT", dlt.topic());
        boolean retained = false;
        for (var h : dlt.headers()) {
            if ("eg.dlt.replay.attempt".equals(h.key())) {
                retained = true;
                assertEquals("1", new String(h.value(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(retained, "写回 DLT 的消息必须保留 eg.dlt.replay.attempt 头，否则隔离计数无法跨 DLT 往返累积");
    }
}
