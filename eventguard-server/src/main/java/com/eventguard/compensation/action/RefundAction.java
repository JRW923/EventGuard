package com.eventguard.compensation.action;

import com.eventguard.gateway.PaymentGateway;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

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
        Object amountObj = params.get("amount");
        BigDecimal amount = amountObj instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        PaymentGateway.RefundResult result = paymentGateway.refund(
                new PaymentGateway.RefundRequest(aggregateId, UUID.randomUUID(), amount));
        return result.success()
                ? "已发起退款，订单 " + aggregateId + "，金额 " + amount + "，退款单号 " + result.refundId()
                : "退款失败：" + result.error();
    }
}
