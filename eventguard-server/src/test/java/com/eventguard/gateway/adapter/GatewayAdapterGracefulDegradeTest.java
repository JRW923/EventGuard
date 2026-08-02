package com.eventguard.gateway.adapter;

import com.eventguard.gateway.PaymentGateway;
import com.eventguard.gateway.config.GatewayProperties;
import com.eventguard.gateway.alipay.AlipaySandboxPaymentGateway;
import com.eventguard.gateway.http.HttpInventoryGateway;
import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.NotificationGateway;
import com.eventguard.gateway.notify.WeComNotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 真实 HTTP 适配器无凭证时的优雅失败路径（不抛异常，返回失败结果）。 */
class GatewayAdapterGracefulDegradeTest {

    private static GatewayProperties props(String payment, String inventory, String notify) {
        // 全部真实 Provider 凭证留空 → 触发无凭证优雅失败
        return new GatewayProperties(payment, inventory, notify, 0.0, 0, "SKU-A:100",
                "", "", "", "", "", 465, "", "", "");
    }

    @Test
    void alipay_without_credentials_returns_failure() {
        PaymentGateway gw = new AlipaySandboxPaymentGateway(props("alipay", "mock", "mock"));
        PaymentGateway.CreatePaymentResult r = gw.createPayment(
                new PaymentGateway.CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50")));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("未配置支付宝");
    }

    @Test
    void wecom_without_webhook_returns_failure() {
        NotificationGateway gw = new WeComNotificationGateway(
                props("mock", "mock", "wecom"), mock(JdbcTemplate.class), new ObjectMapper());
        NotificationGateway.SendResult r = gw.send(
                new NotificationGateway.NotificationMessage("DELAY", "user-1", UUID.randomUUID(), Map.of()));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("未配置企业微信");
    }

    @Test
    void http_inventory_without_url_returns_failure_and_zero_stock() {
        InventoryGateway gw = new HttpInventoryGateway(props("mock", "http", "mock"), new ObjectMapper());
        InventoryGateway.ReservationResult r = gw.reserve(
                new InventoryGateway.ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), "SKU-A", 5));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("未配置 EG_INVENTORY_SERVICE_URL");
        assertThat(gw.currentStock("SKU-A")).isZero();
    }
}
