package com.eventguard.gateway.notify;

import com.eventguard.gateway.NotificationGateway;
import com.eventguard.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 企业微信群机器人 webhook 通知网关（EG_NOTIFY_PROVIDER=wecom）。
 * <p>
 * D 步「HTTP 适配器示例」：POST JSON 到群机器人 webhook，同时写 notification_log 留痕。
 * 未配置 {@code EG_NOTIFY_WECOM_WEBHOOK} 时返回失败（不抛异常）。
 */
@Component
@ConditionalOnProperty(name = "eg.notify.provider", havingValue = "wecom")
public class WeComNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(WeComNotificationGateway.class);

    private final GatewayProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WeComNotificationGateway(GatewayProperties properties, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SendResult send(NotificationMessage msg) {
        String webhook = properties.getWecomWebhookUrl();
        if (webhook.isBlank()) {
            log.warn("[通知-wecom] 未配置 EG_NOTIFY_WECOM_WEBHOOK");
            return new SendResult(false, "wecom", "未配置企业微信 webhook");
        }
        try {
            String text = "【EventGuard】" + typeLabel(msg.type()) + " 订单 " + msg.orderId()
                    + (msg.payload() != null && msg.payload().get("detail") != null ? "：" + msg.payload().get("detail") : "");
            String body = objectMapper.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 企业微信返回 {"errcode":0,"errmsg":"ok"}
            boolean ok = resp.statusCode() == 200 && resp.body().contains("\"errcode\":0");
            recordLog(msg, ok, resp.body());
            return new SendResult(ok, "wecom", ok ? null : "webhook 响应: " + abbreviate(resp.body()));
        } catch (Exception e) {
            log.warn("[通知-wecom] 发送失败: {}", e.getMessage());
            recordLog(msg, false, e.getMessage());
            return new SendResult(false, "wecom", e.getMessage());
        }
    }

    private void recordLog(NotificationMessage msg, boolean ok, String detail) {
        try {
            String payloadJson = msg.payload() == null ? "{}" : objectMapper.writeValueAsString(msg.payload());
            jdbc.update(
                    "INSERT INTO notification_log (id, aggregate_id, notification_type, recipient, channel, status, payload) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), msg.orderId(), msg.type(), msg.recipient(), "wecom",
                    ok ? "SENT" : "FAILED", payloadJson);
        } catch (Exception e) {
            log.warn("[通知-wecom] 记录 notification_log 失败: {}", e.getMessage());
        }
    }

    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "DELAY" -> "订单延迟通知";
            case "OUT_OF_STOCK" -> "库存缺货通知";
            case "REFUND" -> "退款通知";
            case "PAYMENT_FAILED" -> "支付失败通知";
            default -> "系统通知";
        };
    }

    private String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }
}
