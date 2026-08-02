package com.eventguard.gateway.alipay;

import com.eventguard.gateway.PaymentGateway;
import com.eventguard.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 支付宝沙箱支付网关（EG_PAYMENT_PROVIDER=alipay）：走支付宝开放平台沙箱 gateway.do。
 * <p>
 * 这是 D 步「HTTP 适配器示例」：RSA2 签名 + POST 沙箱网关 + 统一返回 {@link CreatePaymentResult}。
 * 未配置 {@code EG_ALIPAY_APP_ID/EG_ALIPAY_PRIVATE_KEY} 时优雅返回失败（不抛异常），
 * 生产接入只需在 .env 填沙箱（或正式）密钥即可，无需改代码。
 */
@Component
@ConditionalOnProperty(name = "eg.payment.provider", havingValue = "alipay")
public class AlipaySandboxPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(AlipaySandboxPaymentGateway.class);

    private final GatewayProperties properties;
    private final HttpClient httpClient;

    public AlipaySandboxPaymentGateway(GatewayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    }

    @Override
    public CreatePaymentResult createPayment(CreatePaymentRequest req) {
        if (properties.getAlipayAppId().isBlank() || properties.getAlipayPrivateKey().isBlank()) {
            log.warn("[支付宝] 未配置 EG_ALIPAY_APP_ID / EG_ALIPAY_PRIVATE_KEY，无法发起支付");
            return new CreatePaymentResult(false, null, null, "未配置支付宝沙箱密钥");
        }
        Map<String, String> biz = new TreeMap<>();
        biz.put("out_trade_no", req.commandId().toString());
        biz.put("total_amount", req.amount() != null ? req.amount().setScale(2).toString() : "0.00");
        biz.put("subject", "EventGuard 订单 " + req.orderId());
        biz.put("product_code", "QUICK_WAP_WAY");
        try {
            String resp = postGateway("alipay.trade.wap.pay", biz);
            // 沙箱返回 form 表单 / JSON，此处示例仅透传；支付单号即 out_trade_no（回调按它关联）
            log.info("[支付宝] 下单响应(order={}) 截断: {}", req.orderId(), abbreviate(resp));
            return new CreatePaymentResult(true, req.commandId().toString(), properties.getAlipayGateway(), null);
        } catch (Exception e) {
            log.warn("[支付宝] 下单失败: {}", e.getMessage());
            return new CreatePaymentResult(false, null, null, e.getMessage());
        }
    }

    @Override
    public QueryPaymentResult queryPayment(String externalRef) {
        if (properties.getAlipayAppId().isBlank()) {
            return new QueryPaymentResult(false, "FAILED", "未配置支付宝沙箱密钥");
        }
        Map<String, String> biz = new TreeMap<>();
        biz.put("out_trade_no", externalRef);
        try {
            String resp = postGateway("alipay.trade.query", biz);
            return new QueryPaymentResult(true, resp.contains("TRADE_SUCCESS") ? "SUCCEEDED" : "PENDING", null);
        } catch (Exception e) {
            return new QueryPaymentResult(false, "FAILED", e.getMessage());
        }
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        if (properties.getAlipayAppId().isBlank() || properties.getAlipayPrivateKey().isBlank()) {
            return new RefundResult(false, null, "未配置支付宝沙箱密钥");
        }
        Map<String, String> biz = new TreeMap<>();
        biz.put("out_trade_no", req.commandId().toString());
        biz.put("refund_amount", req.amount() != null ? req.amount().setScale(2).toString() : "0.00");
        try {
            String resp = postGateway("alipay.trade.refund", biz);
            log.info("[支付宝] 退款响应(order={}) 截断: {}", req.orderId(), abbreviate(resp));
            return new RefundResult(true, "alipay-refund-" + UUID.randomUUID(), null);
        } catch (Exception e) {
            return new RefundResult(false, null, e.getMessage());
        }
    }

    /** 构造 RSA2 签名请求并 POST 沙箱网关。 */
    private String postGateway(String method, Map<String, String> biz) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.getAlipayAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("version", "1.0");
        params.put("notify_url", "http://eventguard-server:8080/gateway/callback/alipay");
        params.put("biz_content", toJson(biz));

        String content = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String sign = rsa2Sign(content, properties.getAlipayPrivateKey());
        String full = content + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getAlipayGateway()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(full))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String rsa2Sign(String content, String privateKeyPem) throws Exception {
        String pkcs8 = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private String toJson(Map<String, String> m) {
        return m.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }
}
