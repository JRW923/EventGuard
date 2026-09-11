package com.eventguard.gateway;

/**
 * SKU 运营状态。与库存数字解耦：缺货不等于库存为 0，反之亦然。
 * <ul>
 *   <li>{@link #ON_SALE} 正常售卖</li>
 *   <li>{@link #OUT_OF_STOCK} 缺货，拒绝新的预留（库存数字原样保留）</li>
 * </ul>
 */
public enum SkuStatus {
    ON_SALE,
    OUT_OF_STOCK
}
