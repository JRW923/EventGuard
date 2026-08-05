package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.anomaly.history.RecentAlertsBuffer;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RecentAlertsBuffer recentAlertsBuffer;

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public AnomalyAlertConsumer(AnomalyWebSocketHandler webSocketHandler, ObjectMapper objectMapper,
                                RecentAlertsBuffer recentAlertsBuffer) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
        this.recentAlertsBuffer = recentAlertsBuffer;
    }

    @KafkaListener(topics = "anomaly-alerts", groupId = "anomaly-ws")
    public void on(String raw) {
        try {
            AnomalyAlert alert = objectMapper.readValue(raw, AnomalyAlert.class);
            log.info("收到异常告警: anomaly_id={} rule_id={} source={} priority={}",
                    alert.getAnomalyId(), alert.getRuleId(), alert.getSource(), alert.getPriority());
            if (metrics != null) {
                metrics.counter("eventguard.anomaly.alert.received", "rule_id",
                        alert.getRuleId() != null ? alert.getRuleId() : "unknown",
                        "level", alert.getLevel() != null ? alert.getLevel() : "unknown");
            }
            // 先入环形缓冲再广播：WS 会话断开时告警仍被保留，前端重连后经 /alerts/recent 补拉
            recentAlertsBuffer.add(alert);
            webSocketHandler.broadcast(alert);
        } catch (Exception e) {
            log.error("反序列化/推送异常告警失败: {}", e.getMessage(), e);
        }
    }
}
