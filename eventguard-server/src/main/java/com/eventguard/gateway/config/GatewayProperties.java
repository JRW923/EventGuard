package com.eventguard.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关配置：Provider 选择与 Mock 行为参数。
 * <ul>
 *   <li>{@code EG_PAYMENT_PROVIDER=mock|alipay}（默认 mock）</li>
 *   <li>{@code EG_INVENTORY_PROVIDER=mock|http}（默认 mock）</li>
 *   <li>{@code EG_NOTIFY_PROVIDER=mock|wecom|smtp}（默认 mock）</li>
 *   <li>{@code EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE=0.0~1.0} mock 支付失败率（演示异常流）</li>
 *   <li>{@code EG_GATEWAY_MOCK_SKUS=SKU-A:100,SKU-B:5} 内存库存种子（K-V 用 : 分隔，条目用 , 分隔）</li>
 * </ul>
 */
@Component
public class GatewayProperties {

    private final String paymentProvider;
    private final String inventoryProvider;
    private final String notifyProvider;
    private final double paymentFailureRate;
    private final long paymentDelayMs;
    private final Map<String, Integer> skus;

    public GatewayProperties(
            @Value("${EG_PAYMENT_PROVIDER:mock}") String paymentProvider,
            @Value("${EG_INVENTORY_PROVIDER:mock}") String inventoryProvider,
            @Value("${EG_NOTIFY_PROVIDER:mock}") String notifyProvider,
            @Value("${EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE:0.0}") double paymentFailureRate,
            @Value("${EG_GATEWAY_MOCK_PAYMENT_DELAY_MS:0}") long paymentDelayMs,
            @Value("${EG_GATEWAY_MOCK_SKUS:SKU-A:100,SKU-B:5}") String skusCsv) {
        this.paymentProvider = paymentProvider;
        this.inventoryProvider = inventoryProvider;
        this.notifyProvider = notifyProvider;
        this.paymentFailureRate = paymentFailureRate;
        this.paymentDelayMs = paymentDelayMs;
        this.skus = parseSkus(skusCsv);
    }

    private static Map<String, Integer> parseSkus(String csv) {
        Map<String, Integer> m = new HashMap<>();
        if (csv == null || csv.isBlank()) return m;
        for (String entry : csv.split(",")) {
            String[] kv = entry.split(":");
            if (kv.length == 2) {
                try {
                    m.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return m;
    }

    public String getPaymentProvider() { return paymentProvider; }
    public String getInventoryProvider() { return inventoryProvider; }
    public String getNotifyProvider() { return notifyProvider; }
    public double getPaymentFailureRate() { return paymentFailureRate; }
    public long getPaymentDelayMs() { return paymentDelayMs; }
    public Map<String, Integer> getSkus() { return Map.copyOf(skus); }
}
