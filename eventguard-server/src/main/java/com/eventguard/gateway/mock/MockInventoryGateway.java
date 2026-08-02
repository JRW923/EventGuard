package com.eventguard.gateway.mock;

import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.config.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 库存网关：内存 SKU 库存表（EG_INVENTORY_PROVIDER=mock，缺省值）。reserve 递减库存、
 * 不足返回失败；currentStock 从内存读，替换 RuleContextLoader 硬编码的 1000
 * （R005 库存越界规则因此真实可触发）。库存种子来自 {@code EG_GATEWAY_MOCK_SKUS}。
 */
@Component
@ConditionalOnProperty(name = "eg.inventory.provider", havingValue = "mock", matchIfMissing = true)
public class MockInventoryGateway implements InventoryGateway {

    private final ConcurrentHashMap<String, Integer> stock;
    private final ConcurrentHashMap<UUID, ReservationResult> reservedByCommandId = new ConcurrentHashMap<>();

    public MockInventoryGateway(GatewayProperties properties) {
        this.stock = new ConcurrentHashMap<>(properties.getSkus());
    }

    @Override
    public ReservationResult reserve(ReserveRequest req) {
        // 幂等：同一 commandId（订单命令幂等键）已预留过则返回缓存结果，避免重放/重试重复扣减
        ReservationResult cached = reservedByCommandId.get(req.commandId());
        if (cached != null) return cached;
        // 原子递减：成功则扣库存并返回剩余，不足则不动库存返回失败
        boolean[] reserved = {false};
        Integer remaining = stock.compute(req.skuId(), (sku, current) -> {
            int base = (current == null) ? 0 : current;
            if (base < req.quantity()) {
                reserved[0] = false;
                return base; // 不足不扣
            }
            reserved[0] = true;
            return base - req.quantity();
        });
        ReservationResult result = reserved[0]
                ? new ReservationResult(true, remaining, null)
                : new ReservationResult(false, remaining, "库存不足: " + req.skuId());
        reservedByCommandId.put(req.commandId(), result);
        return result;
    }

    @Override
    public ReleaseResult release(ReleaseRequest req) {
        stock.computeIfPresent(req.skuId(), (sku, current) -> current + req.quantity());
        return new ReleaseResult(true, null);
    }

    @Override
    public int currentStock(String skuId) {
        return stock.getOrDefault(skuId, 0);
    }

    @Override
    public MarkOutOfStockResult markOutOfStock(String skuId) {
        stock.put(skuId, 0);
        return new MarkOutOfStockResult(true, null);
    }

    /** 仅供测试：设置某 SKU 库存。 */
    public void setStock(String skuId, int qty) {
        stock.put(skuId, qty);
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(stock);
    }
}
