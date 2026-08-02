package com.eventguard.gateway.mock;

import com.eventguard.gateway.PaymentGateway;
import com.eventguard.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * Mock 支付网关：默认实现。按 {@link GatewayProperties} 的失败率/延迟模拟真实网关行为。
 * A 步同步返回结果（createPayment 即出支付单号）；B 步异步回调模式由调度器触发
 * POST /gateway/callback/payment，此处接口与结果已就绪，仅缺异步投递。
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final GatewayProperties properties;
    private final Random random;

    public MockPaymentGateway(GatewayProperties properties) {
        this.properties = properties;
        this.random = new Random();
    }

    @Override
    public CreatePaymentResult createPayment(CreatePaymentRequest req) {
        if (random.nextDouble() < properties.getPaymentFailureRate()) {
            return new CreatePaymentResult(false, null, null, "mock 网关支付失败（失败率配置）");
        }
        // paymentId 即网关侧支付单号（external_ref），供异步回调反查关联
        String paymentId = "mockpay-" + UUID.randomUUID();
        return new CreatePaymentResult(true, paymentId, "https://mock-gateway.local/pay/" + paymentId, null);
    }

    @Override
    public QueryPaymentResult queryPayment(String externalRef) {
        return new QueryPaymentResult(true, "SUCCEEDED", null);
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        return new RefundResult(true, "mockrefund-" + UUID.randomUUID(), null);
    }
}
