package com.eventguard.gateway.mock;

import com.eventguard.gateway.PaymentGateway;
import com.eventguard.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mock 支付网关单测：成功路径生成支付单号、失败率 0 必成功。 */
class MockPaymentGatewayTest {

    private static MockPaymentGateway gateway(double failureRate) {
        return new MockPaymentGateway(new GatewayProperties("mock", "mock", "mock",
                failureRate, 0, "SKU-A:100"));
    }

    @Test
    void create_payment_succeeds_with_zero_failure_rate() {
        var gw = gateway(0.0);
        var r = gw.createPayment(new PaymentGateway.CreatePaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("99.00")));
        assertThat(r.success()).isTrue();
        assertThat(r.paymentId()).isNotNull().startsWith("mockpay-");
        assertThat(r.payUrl()).contains(r.paymentId());
    }

    @Test
    void create_payment_always_fails_with_rate_one() {
        var gw = gateway(1.0);
        var r = gw.createPayment(new PaymentGateway.CreatePaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("99.00")));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("失败");
    }

    @Test
    void refund_succeeds_with_refund_id() {
        var gw = gateway(0.0);
        var r = gw.refund(new PaymentGateway.RefundRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00")));
        assertThat(r.success()).isTrue();
        assertThat(r.refundId()).isNotNull().startsWith("mockrefund-");
    }

    @Test
    void query_payment_returns_succeeded() {
        var gw = gateway(0.0);
        var r = gw.queryPayment("mockpay-123");
        assertThat(r.success()).isTrue();
        assertThat(r.status()).isEqualTo("SUCCEEDED");
    }
}
