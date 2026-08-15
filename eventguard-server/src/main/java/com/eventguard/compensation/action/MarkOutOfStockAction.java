package com.eventguard.compensation.action;

import com.eventguard.gateway.InventoryGateway;
import com.eventguard.compensation.model.CompensationResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 标记缺货补偿动作：经库存网关把 SKU 库存置 0。 */
@Component
public class MarkOutOfStockAction implements CompensationAction {

    private final InventoryGateway inventoryGateway;

    public MarkOutOfStockAction(InventoryGateway inventoryGateway) {
        this.inventoryGateway = inventoryGateway;
    }

    @Override
    public String actionType() { return "MARK_OUT_OF_STOCK"; }

    @Override
    public String defaultRiskLevel() { return "LOW"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        return executeResult(aggregateId, params).getMessage();
    }

    @Override
    public CompensationResult executeResult(UUID aggregateId, Map<String, Object> params) {
        Object sku = params.get("sku");
        InventoryGateway.MarkOutOfStockResult result = inventoryGateway.markOutOfStock(
                sku != null ? sku.toString() : "SKU-unknown");
        return result.success()
                ? CompensationResult.success("已标记 SKU " + sku + " 缺货")
                : CompensationResult.failure("标记缺货失败：" + result.error());
    }
}
