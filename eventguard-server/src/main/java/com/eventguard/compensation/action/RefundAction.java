package com.eventguard.compensation.action;

import com.eventguard.gateway.PaymentGateway;
import com.eventguard.compensation.model.CompensationResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** 退款补偿动作：经支付网关发起真实退款（Mock 默认，D 步可换真实 Provider）。 */
@Component
public class RefundAction implements CompensationAction {

    private final PaymentGateway paymentGateway;

    public RefundAction(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @Override
    public String actionType() { return "REFUND"; }

    @Override
    public String defaultRiskLevel() { return "MEDIUM"; }

    @Override
    public boolean requiresApproval(UUID aggregateId, Map<String, Object> params) {
        Object amountObj = params.get("amount");
        BigDecimal amount = amountObj instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        // 对齐设计文档 7.4.2：退款金额 > 100 需人工审批
        return amount.compareTo(BigDecimal.valueOf(100)) > 0;
    }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        return executeResult(aggregateId, params).getMessage();
    }

    @Override
    public CompensationResult executeResult(UUID aggregateId, Map<String, Object> params) {
        Object amountObj = params.get("amount");
        BigDecimal amount = amountObj instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        Object key = params.get("__idempotency_key");
        UUID commandId = key == null
                ? UUID.nameUUIDFromBytes((aggregateId + ":REFUND:" + amount).getBytes(StandardCharsets.UTF_8))
                : UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8));
        PaymentGateway.RefundResult result = paymentGateway.refund(
                new PaymentGateway.RefundRequest(aggregateId, commandId, amount));
        return result.success()
                ? CompensationResult.success("已发起退款，订单 " + aggregateId + "，金额 " + amount + "，退款单号 " + result.refundId())
                : CompensationResult.failure("退款失败：" + result.error());
    }
}
