package com.eventguard.gateway.mock;

import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.SkuStatus;
import com.eventguard.gateway.mock.MockInventoryGateway.SkuState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mock 库存网关单测：种子解析、预留/释放/缺货/当前库存。 */
class MockInventoryGatewayTest {

    private final MockInventoryGateway gateway = new MockInventoryGateway(
            props("SKU-A:100,SKU-B:5"));

    private static com.eventguard.gateway.config.GatewayProperties props(String skus) {
        return new com.eventguard.gateway.config.GatewayProperties("mock", "mock", "mock",
                0.0, 0, skus);
    }

    private static InventoryGateway.ReserveRequest req(String sku, int qty) {
        return new InventoryGateway.ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), sku, qty);
    }

    private static InventoryGateway.ConfirmRequest confirm(String sku, int qty) {
        return new InventoryGateway.ConfirmRequest(UUID.randomUUID(), UUID.randomUUID(), sku, qty);
    }

    @Test
    void seeds_stock_from_config() {
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(100);
        assertThat(gateway.currentStock("SKU-B")).isEqualTo(5);
        assertThat(gateway.currentStock("SKU-MISSING")).isEqualTo(0);
    }

    @Test
    void reserve_decrements_stock_and_returns_remaining() {
        var r = gateway.reserve(req("SKU-A", 30));
        assertThat(r.success()).isTrue();
        assertThat(r.remainingStock()).isEqualTo(70);
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(70);
    }

    @Test
    void reserve_exactly_all_stock_succeeds() {
        var r = gateway.reserve(req("SKU-B", 5));
        assertThat(r.success()).isTrue();
        assertThat(r.remainingStock()).isZero();
        assertThat(gateway.currentStock("SKU-B")).isZero();
    }

    @Test
    void reserve_insufficient_stock_fails_and_does_not_decrement() {
        var r = gateway.reserve(req("SKU-B", 10));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("库存不足");
        assertThat(gateway.currentStock("SKU-B")).isEqualTo(5);
    }

    @Test
    void reserve_unknown_sku_fails() {
        var r = gateway.reserve(req("SKU-NOPE", 1));
        assertThat(r.success()).isFalse();
    }

    @Test
    void reserve_moves_quantity_to_reserved_not_total() {
        gateway.reserve(req("SKU-A", 30));
        SkuState state = gateway.snapshot().get("SKU-A");
        assertThat(state.total()).isEqualTo(100);   // 物理库存不动
        assertThat(state.reserved()).isEqualTo(30); // 只占预留
        assertThat(state.available()).isEqualTo(70);
    }

    @Test
    void release_restores_stock() {
        gateway.reserve(req("SKU-A", 30));
        var r = gateway.release(new InventoryGateway.ReleaseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SKU-A", 30));
        assertThat(r.success()).isTrue();
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(100);
    }

    @Test
    void release_is_idempotent_per_command_id() {
        UUID commandId = UUID.randomUUID();
        gateway.reserve(new InventoryGateway.ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), "SKU-A", 30));
        var rel = new InventoryGateway.ReleaseRequest(UUID.randomUUID(), commandId, "SKU-A", 30);
        gateway.release(rel);
        gateway.release(rel); // 重复释放同一 commandId 不应二次回补
        assertThat(gateway.snapshot().get("SKU-A").reserved()).isZero();
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(100);
    }

    @Test
    void release_never_drives_reserved_negative() {
        gateway.release(new InventoryGateway.ReleaseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SKU-A", 10));
        assertThat(gateway.snapshot().get("SKU-A").reserved()).isZero();
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(100);
    }

    @Test
    void confirm_turns_reserved_into_actual_deduction() {
        gateway.reserve(req("SKU-A", 30));
        var r = gateway.confirm(confirm("SKU-A", 30));
        assertThat(r.success()).isTrue();
        SkuState state = gateway.snapshot().get("SKU-A");
        assertThat(state.total()).isEqualTo(70);    // 物理库存真正扣掉
        assertThat(state.reserved()).isZero();      // 预占释放
        assertThat(state.available()).isEqualTo(70); // 可售不变（确认不释放给他人）
    }

    @Test
    void confirm_is_idempotent_per_command_id() {
        UUID commandId = UUID.randomUUID();
        gateway.reserve(req("SKU-A", 30));
        var req = new InventoryGateway.ConfirmRequest(UUID.randomUUID(), commandId, "SKU-A", 30);
        gateway.confirm(req);
        gateway.confirm(req);
        assertThat(gateway.snapshot().get("SKU-A").total()).isEqualTo(70);
        assertThat(gateway.snapshot().get("SKU-A").reserved()).isZero();
    }

    @Test
    void confirm_fails_without_enough_reserved_and_leaves_stock_untouched() {
        var r = gateway.confirm(confirm("SKU-A", 10));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("预留不足");
        assertThat(gateway.snapshot().get("SKU-A")).isEqualTo(new SkuState(100, 0, SkuStatus.ON_SALE));
    }

    @Test
    void mark_out_of_stock_keeps_stock_but_blocks_reserve() {
        var r = gateway.markOutOfStock("SKU-A");
        assertThat(r.success()).isTrue();
        // 只改状态，不清零库存数字（清零会误伤仍有余量可售的 SKU）
        assertThat(gateway.snapshot().get("SKU-A").status()).isEqualTo(SkuStatus.OUT_OF_STOCK);
        assertThat(gateway.snapshot().get("SKU-A").total()).isEqualTo(100);
        assertThat(gateway.reserve(req("SKU-A", 1)).success()).isFalse();
    }

    @Test
    void concurrent_reserve_never_oversells() throws Exception {
        gateway.setStock("SKU-A", 10);
        int threads = 8;
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    barrier.await();
                    if (gateway.reserve(req("SKU-A", 3)).success()) ok.incrementAndGet();
                    return null;
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // 库存 10、每次要 3 → 最多成功 3 次，绝不会 4 次
        assertThat(ok.get()).isEqualTo(3);
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(1);
    }

    @Test
    void set_stock_then_snapshot_reflects() {
        gateway.setStock("SKU-A", 7);
        assertThat(gateway.snapshot().get("SKU-A")).isEqualTo(new SkuState(7, 0, SkuStatus.ON_SALE));
    }
}
