package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 消费 anomaly-alerts topic，推送到 WebSocket 前端 */
@Component
public class AnomalyAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyAlertConsumer.class);

    private final AnomalyWebSocketHandler webSocketHandler;

    public AnomalyAlertConsumer(AnomalyWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @KafkaListener(topics = "anomaly-alerts", groupId = "anomaly-ws")
    public void on(AnomalyAlert alert) {
        log.info("收到异常告警: anomaly_id={} rule_id={} source={} priority={}",
                alert.getAnomalyId(), alert.getRuleId(), alert.getSource(), alert.getPriority());
        try {
            webSocketHandler.broadcast(alert);
        } catch (Exception e) {
            log.error("推送 WebSocket 失败: {}", e.getMessage(), e);
        }
    }
}
