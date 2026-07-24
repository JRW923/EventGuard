package com.eventguard.compensation.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 冻结订单补偿动作。 */
@Component
public class FreezeOrderAction implements CompensationAction {

    @Override
    public String actionType() { return "FREEZE_ORDER"; }

    @Override
    public String defaultRiskLevel() { return "HIGH"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        return "已冻结订单 " + aggregateId;
    }
}
