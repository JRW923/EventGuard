package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 anomaly-alerts topic，推送到 WebSocket 前端。
 * ponytail: 全局 value-deserializer 为 StringDeserializer（Debezium 字符串消息），
 * 此 topic 的 Python 生产者发 JSON 字符串，故监听器接收 String 再手动反序列化，
 * 避免 String→POJO 消息转换失败导致告警静默丢弃。
 */
@Component
public class AnomalyAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyAlertConsumer.class);

    private final AnomalyWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    public AnomalyAlertConsumer(AnomalyWebSocketHandler webSocketHandler, ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "anomaly-alerts", groupId = "anomaly-ws")
    public void on(String raw) {
        try {
            AnomalyAlert alert = objectMapper.readValue(raw, AnomalyAlert.class);
            log.info("收到异常告警: anomaly_id={} rule_id={} source={} priority={}",
                    alert.getAnomalyId(), alert.getRuleId(), alert.getSource(), alert.getPriority());
            webSocketHandler.broadcast(alert);
        } catch (Exception e) {
            log.error("反序列化/推送异常告警失败: {}", e.getMessage(), e);
        }
    }
}
