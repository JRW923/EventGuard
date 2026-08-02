package com.eventguard.gateway.config;

import org.springframework.beans.factory.annotation.Autowired;
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
    // D 步真实 Provider 凭证
    private final String alipayAppId;
    private final String alipayPrivateKey;
    private final String alipayGateway;
    private final String wecomWebhookUrl;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final String inventoryServiceUrl;

    /**
     * 便捷构造：仅 mock 参数（测试用），真实 Provider 凭证留空。
     */
    public GatewayProperties(String paymentProvider, String inventoryProvider, String notifyProvider,
                             double paymentFailureRate, long paymentDelayMs, String skusCsv) {
        this(paymentProvider, inventoryProvider, notifyProvider, paymentFailureRate, paymentDelayMs, skusCsv,
                "", "", "", "", "", 465, "", "", "");
    }

    @Autowired
    public GatewayProperties(
            @Value("${EG_PAYMENT_PROVIDER:mock}") String paymentProvider,
            @Value("${EG_INVENTORY_PROVIDER:mock}") String inventoryProvider,
            @Value("${EG_NOTIFY_PROVIDER:mock}") String notifyProvider,
            @Value("${EG_GATEWAY_MOCK_PAYMENT_FAILURE_RATE:0.0}") double paymentFailureRate,
            @Value("${EG_GATEWAY_MOCK_PAYMENT_DELAY_MS:0}") long paymentDelayMs,
            @Value("${EG_GATEWAY_MOCK_SKUS:SKU-A:100,SKU-B:5}") String skusCsv,
            @Value("${EG_ALIPAY_APP_ID:}") String alipayAppId,
            @Value("${EG_ALIPAY_PRIVATE_KEY:}") String alipayPrivateKey,
            @Value("${EG_ALIPAY_GATEWAY:https://openapi-sandbox.dl.alipaydev.com/gateway.do}") String alipayGateway,
            @Value("${EG_NOTIFY_WECOM_WEBHOOK:}") String wecomWebhookUrl,
            @Value("${EG_SMTP_HOST:}") String smtpHost,
            @Value("${EG_SMTP_PORT:465}") int smtpPort,
            @Value("${EG_SMTP_USER:}") String smtpUser,
            @Value("${EG_SMTP_PASSWORD:}") String smtpPassword,
            @Value("${EG_INVENTORY_SERVICE_URL:}") String inventoryServiceUrl) {
        this.paymentProvider = paymentProvider;
        this.inventoryProvider = inventoryProvider;
        this.notifyProvider = notifyProvider;
        this.paymentFailureRate = paymentFailureRate;
        this.paymentDelayMs = paymentDelayMs;
        this.skus = parseSkus(skusCsv);
        this.alipayAppId = alipayAppId;
        this.alipayPrivateKey = alipayPrivateKey;
        this.alipayGateway = alipayGateway;
        this.wecomWebhookUrl = wecomWebhookUrl;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.inventoryServiceUrl = inventoryServiceUrl;
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
    public String getAlipayAppId() { return alipayAppId; }
    public String getAlipayPrivateKey() { return alipayPrivateKey; }
    public String getAlipayGateway() { return alipayGateway; }
    public String getWecomWebhookUrl() { return wecomWebhookUrl; }
    public String getSmtpHost() { return smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public String getSmtpUser() { return smtpUser; }
    public String getSmtpPassword() { return smtpPassword; }
    public String getInventoryServiceUrl() { return inventoryServiceUrl; }
}
