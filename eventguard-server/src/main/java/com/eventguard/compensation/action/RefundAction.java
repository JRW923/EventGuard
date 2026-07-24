package com.eventguard.compensation.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 退款补偿动作。 */
@Component
public class RefundAction implements CompensationAction {

    @Override
    public String actionType() { return "REFUND"; }

    @Override
    public String defaultRiskLevel() { return "MEDIUM"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        Object amount = params.get("amount");
        return "已发起退款，订单 " + aggregateId + "，金额 " + amount;
    }
}
