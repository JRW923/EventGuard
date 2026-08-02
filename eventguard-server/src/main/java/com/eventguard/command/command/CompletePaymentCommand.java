package com.eventguard.command.command;

import java.util.UUID;

/**
 * 支付完成命令：由网关异步回调（成功）触发，聚合 raise PaymentCompletedEvent → PAID。
 * commandId 用回调幂等键（网关回调重放时命中 command_log 返回缓存结果）。
 */
public record CompletePaymentCommand(UUID commandId, UUID orderId, String paymentId) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
