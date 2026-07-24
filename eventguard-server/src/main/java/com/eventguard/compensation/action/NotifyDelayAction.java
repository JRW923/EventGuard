package com.eventguard.compensation.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 延迟通知补偿动作。 */
@Component
public class NotifyDelayAction implements CompensationAction {

    @Override
    public String actionType() { return "NOTIFY_DELAY"; }

    @Override
    public String defaultRiskLevel() { return "LOW"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        return "已发送延迟通知，订单 " + aggregateId;
    }
}
