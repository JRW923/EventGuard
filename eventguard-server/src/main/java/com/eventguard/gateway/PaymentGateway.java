package com.eventguard.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 支付网关抽象（Ports &amp; Adapters）。默认 Mock 实现；真实 Provider（如支付宝沙箱）实现同一接口，
 * 由 {@code EG_PAYMENT_PROVIDER} 切换。B 步将支付改为「异步意图+回调」：createPayment 落库 PENDING，
 * 网关回调后派发 CompletePaymentCommand 才置 PAID。
 */
public interface PaymentGateway {

    /** 创建支付单：返回网关侧支付单号（external_ref），供异步回调反查关联。 */
    CreatePaymentResult createPayment(CreatePaymentRequest req);

    /** 查单：真实网关回调兜底/对账用。 */
    QueryPaymentResult queryPayment(String externalRef);

    /** 退款：返回退款单号。 */
    RefundResult refund(RefundRequest req);

    // —— 请求 / 结果记录 ——

    record CreatePaymentRequest(UUID orderId, UUID commandId, BigDecimal amount) {}

    record CreatePaymentResult(boolean success, String paymentId, String payUrl, String error) {}

    record QueryPaymentResult(boolean success, String status, String error) {}

    record RefundRequest(UUID orderId, UUID commandId, BigDecimal amount) {}

    record RefundResult(boolean success, String refundId, String error) {}
}
