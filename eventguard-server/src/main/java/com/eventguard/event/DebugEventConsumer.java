package com.eventguard.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 临时消费者：验证 CDC 链路是否打通。
 * M2 起会被 OrderViewProjection 等正式消费者替代。
 */
@Component
public class DebugEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DebugEventConsumer.class);

    @KafkaListener(topics = "domain-events", groupId = "debug-consumer")
    public void on(ConsumerRecord<String, JsonNode> record) {
        log.info("[CDC 验证] key={}, partition={}, offset={}, payload={}",
                record.key(), record.partition(), record.offset(), record.value());
    }
}
