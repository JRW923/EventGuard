package com.eventguard.compensation.action;

import com.eventguard.gateway.NotificationGateway;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 延迟通知补偿动作：经通知网关发送延迟通知（Mock 默认，D 步可换企业微信/SMTP）。 */
@Component
public class NotifyDelayAction implements CompensationAction {

    private final NotificationGateway notificationGateway;

    public NotifyDelayAction(NotificationGateway notificationGateway) {
        this.notificationGateway = notificationGateway;
    }

    @Override
    public String actionType() { return "NOTIFY_DELAY"; }

    @Override
    public String defaultRiskLevel() { return "LOW"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        String recipient = params.get("recipient") != null ? params.get("recipient").toString() : "order-user";
        NotificationGateway.SendResult result = notificationGateway.send(
                new NotificationGateway.NotificationMessage("DELAY", recipient, aggregateId, Map.of()));
        return result.success()
                ? "已发送延迟通知，订单 " + aggregateId + "（渠道 " + result.channel() + "）"
                : "延迟通知发送失败：" + result.error();
    }
}
