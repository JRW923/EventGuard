package com.eventguard.gateway;

import java.util.UUID;

/**
 * 库存网关抽象（Ports &amp; Adapters）。默认 Mock 实现；真实 Provider 由 {@code EG_INVENTORY_PROVIDER}
 * 切换。B 步将 ReserveInventoryCommand 改为「先调网关再按结果 raise 事件」：成功 → InventoryReservedEvent，
 * 库存不足 → InventoryReservationFailedEvent；R005 规则从 currentStock 读真实库存。
 */
public interface InventoryGateway {

    /** 预留库存：成功返回剩余库存；库存不足返回失败。 */
    ReservationResult reserve(ReserveRequest req);

    /** 释放预留（退款/取消时回补库存）。 */
    ReleaseResult release(ReleaseRequest req);

    /** 当前库存（替换 RuleContextLoader 硬编码的 1000）。 */
    int currentStock(String skuId);

    /** 标记 SKU 缺货（补偿动作 MARK_OUT_OF_STOCK 用）。 */
    MarkOutOfStockResult markOutOfStock(String skuId);

    // —— 请求 / 结果记录 ——

    record ReserveRequest(UUID orderId, UUID commandId, String skuId, int quantity) {}

    record ReservationResult(boolean success, int remainingStock, String error) {}

    record ReleaseRequest(UUID orderId, UUID commandId, String skuId, int quantity) {}

    record ReleaseResult(boolean success, String error) {}

    record MarkOutOfStockResult(boolean success, String error) {}
}
