package com.eventguard.gateway;

import java.util.Map;
import java.util.UUID;

/**
 * 通知网关抽象（Ports &amp; Adapters）。默认 Mock 实现（写 notification_log）；真实 Provider
 * （企业微信 webhook / SMTP）由 {@code EG_NOTIFY_PROVIDER} 切换。通知类型如 DELAY / OUT_OF_STOCK /
 * REFUND / PAYMENT_FAILED。
 */
public interface NotificationGateway {

    /** 发送通知：成功则写 notification_log 留痕。 */
    SendResult send(NotificationMessage msg);

    record NotificationMessage(String type, String recipient, UUID orderId, Map<String, Object> payload) {}

    record SendResult(boolean success, String channel, String error) {}
}
