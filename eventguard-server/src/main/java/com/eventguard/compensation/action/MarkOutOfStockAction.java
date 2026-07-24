package com.eventguard.compensation.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 标记缺货补偿动作。 */
@Component
public class MarkOutOfStockAction implements CompensationAction {

    @Override
    public String actionType() { return "MARK_OUT_OF_STOCK"; }

    @Override
    public String defaultRiskLevel() { return "LOW"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        Object sku = params.get("sku");
        return "已标记 SKU " + sku + " 缺货";
    }
}
