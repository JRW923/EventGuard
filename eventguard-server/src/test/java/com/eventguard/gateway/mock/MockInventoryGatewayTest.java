package com.eventguard.gateway.mock;

import com.eventguard.gateway.InventoryGateway;
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
    void release_restores_stock() {
        gateway.reserve(req("SKU-A", 30));
        var r = gateway.release(new InventoryGateway.ReleaseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SKU-A", 30));
        assertThat(r.success()).isTrue();
        assertThat(gateway.currentStock("SKU-A")).isEqualTo(100);
    }

    @Test
    void mark_out_of_stock_sets_stock_to_zero() {
        var r = gateway.markOutOfStock("SKU-A");
        assertThat(r.success()).isTrue();
        assertThat(gateway.currentStock("SKU-A")).isZero();
    }

    @Test
    void set_stock_then_snapshot_reflects() {
        gateway.setStock("SKU-A", 7);
        assertThat(gateway.snapshot().get("SKU-A")).isEqualTo(7);
    }
}
