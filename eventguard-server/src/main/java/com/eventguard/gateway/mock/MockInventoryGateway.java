package com.eventguard.gateway.mock;

import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.SkuStatus;
import com.eventguard.gateway.config.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 库存网关：内存 SKU 库存表（EG_INVENTORY_PROVIDER=mock，缺省值）。库存种子来自 {@code EG_GATEWAY_MOCK_SKUS}。
 * <p>
 * 库存三维模型：{@code total} 物理总量、{@code reserved} 已预留未确认、{@code status} 运营状态，
 * 可售 {@code available = total - reserved} 派生不落库（落库就要维护一致性）。
 * 预留走 {@code reserved += N} 而不是直接扣 {@code total}，因此释放时知道该回补多少——
 * 单值模型（预留即扣减）无法回答这个问题，是 release 做不了闭环的根因。
 * <p>
 * ponytail: 三个维度必须放在同一个值对象里，靠 {@code ConcurrentHashMap.compute} 保证原子性；
 * 拆成多个 Map 会丢掉原子性，出现「库存已扣但状态未改」的中间态。
 */
@Component
@ConditionalOnProperty(name = "eg.inventory.provider", havingValue = "mock", matchIfMissing = true)
public class MockInventoryGateway implements InventoryGateway {

    /** 库存三维状态。record 不可变，每次变更整体替换，避免部分更新。 */
    public record SkuState(int total, int reserved, SkuStatus status) {
        public SkuState {
            if (total < 0 || reserved < 0) throw new IllegalArgumentException("库存不能为负");
        }

        public int available() {
            return total - reserved;
        }
    }

    private final ConcurrentHashMap<String, SkuState> skus;
    private final ConcurrentHashMap<UUID, ReservationResult> reservedByCommandId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReleaseResult> releasedByCommandId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConfirmResult> confirmedByCommandId = new ConcurrentHashMap<>();

    public MockInventoryGateway(GatewayProperties properties) {
        this.skus = new ConcurrentHashMap<>();
        properties.getSkus().forEach((sku, qty) ->
                skus.put(sku, new SkuState(qty, 0, SkuStatus.ON_SALE)));
    }

    @Override
    public ReservationResult reserve(ReserveRequest req) {
        // 幂等：同一 commandId 已预留过则返回缓存结果，避免重放/重试重复占用
        ReservationResult cached = reservedByCommandId.get(req.commandId());
        if (cached != null) return cached;

        boolean[] ok = {false};
        int[] remaining = {0};
        // 原子读写：判断与占用在同一次 compute 内完成，并发抢同一 SKU 不会超卖
        skus.compute(req.skuId(), (sku, state) -> {
            if (state == null || state.status() != SkuStatus.ON_SALE || state.available() < req.quantity()) {
                ok[0] = false;
                remaining[0] = state == null ? 0 : state.available();
                return state; // 不足不占
            }
            ok[0] = true;
            SkuState next = new SkuState(state.total(), state.reserved() + req.quantity(), state.status());
            remaining[0] = next.available();
            return next;
        });
        ReservationResult result = ok[0]
                ? new ReservationResult(true, remaining[0], null)
                : new ReservationResult(false, remaining[0], "库存不足: " + req.skuId());
        reservedByCommandId.put(req.commandId(), result);
        return result;
    }

    @Override
    public ReleaseResult release(ReleaseRequest req) {
        // 幂等：同一 commandId 重复释放只生效一次，避免 reserved 被扣成负数
        ReleaseResult cached = releasedByCommandId.get(req.commandId());
        if (cached != null) return cached;

        skus.computeIfPresent(req.skuId(), (sku, state) ->
                new SkuState(state.total(), Math.max(0, state.reserved() - req.quantity()), state.status()));
        ReleaseResult result = new ReleaseResult(true, null);
        releasedByCommandId.put(req.commandId(), result);
        return result;
    }

    /**
     * 确认预留：预占量转实际扣减，{@code total} 与 {@code reserved} 同时减，{@code available} 不变。
     * 少了这一步，reserved 会一直挂着（只有取消才释放），库存永远对不上账。
     */
    @Override
    public ConfirmResult confirm(ConfirmRequest req) {
        // 幂等：同一 commandId 重复确认只扣一次
        ConfirmResult cached = confirmedByCommandId.get(req.commandId());
        if (cached != null) return cached;

        boolean[] ok = {false};
        skus.computeIfPresent(req.skuId(), (sku, state) -> {
            if (state.reserved() < req.quantity()) {
                ok[0] = false;
                return state; // 预留不足不扣
            }
            ok[0] = true;
            return new SkuState(state.total() - req.quantity(),
                    state.reserved() - req.quantity(), state.status());
        });
        ConfirmResult result = ok[0]
                ? new ConfirmResult(true, null)
                : new ConfirmResult(false, "预留不足，无法确认: " + req.skuId());
        confirmedByCommandId.put(req.commandId(), result);
        return result;
    }

    /**
     * 可售库存 {@code total - reserved}。R005 规则依赖此语义（{@code 预留量 > 可售量} 才命中），
     * 返回 {@code total} 会让规则几乎永不触发。
     */
    @Override
    public int currentStock(String skuId) {
        SkuState state = skus.get(skuId);
        return state == null ? 0 : state.available();
    }

    /**
     * 只改运营状态，不清零库存数字：清零会误伤「剩 3 件但这个订单要 5 件」这类仍有余量可售的 SKU。
     * 原先的 {@code stock.put(sku, 0)} 是破坏性操作，且无法区分「卖光了」和「运营下架」。
     */
    @Override
    public MarkOutOfStockResult markOutOfStock(String skuId) {
        skus.compute(skuId, (sku, state) -> state == null
                ? new SkuState(0, 0, SkuStatus.OUT_OF_STOCK)
                : new SkuState(state.total(), state.reserved(), SkuStatus.OUT_OF_STOCK));
        return new MarkOutOfStockResult(true, null);
    }

    /** 仅供测试：设置某 SKU 物理库存（重置预留与状态）。 */
    public void setStock(String skuId, int qty) {
        skus.put(skuId, new SkuState(qty, 0, SkuStatus.ON_SALE));
    }

    public Map<String, SkuState> snapshot() {
        return Map.copyOf(skus);
    }
}
