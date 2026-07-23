package com.eventguard.command.handler;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.command.command.*;
import com.eventguard.common.dto.CommandResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 订单命令处理器：所有订单命令统一走「幂等检查 → 事务内加载+处理+保存 → 写命令日志」。
 * 重试由 CommandRetryTemplate 包装，每次重试开启新事务。
 */
@Service
public class OrderCommandHandler {

    private final AggregateRepository aggregateRepository;
    private final CommandLogRepository commandLogRepository;
    private final CommandRetryTemplate retryTemplate;
    private final TransactionTemplate transactionTemplate;

    public OrderCommandHandler(AggregateRepository aggregateRepository,
                               CommandLogRepository commandLogRepository,
                               CommandRetryTemplate retryTemplate,
                               PlatformTransactionManager transactionManager) {
        this.aggregateRepository = aggregateRepository;
        this.commandLogRepository = commandLogRepository;
        this.retryTemplate = retryTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CommandResult handle(CreateOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(PayOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(FailPaymentCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(RetryPaymentCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(ReserveInventoryCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(ConfirmOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(ShipOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(DeliverOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(CloseOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(CancelOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(RefundOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    /**
     * 通用执行模板：幂等检查 + 事务内加载/处理/保存 + 命令日志记录。
     */
    private CommandResult execute(Command cmd, Consumer<OrderAggregate> action) {
        // 1. 幂等检查（事务外）
        Optional<CommandResult> existing = commandLogRepository.loadResult(cmd.getCommandId());
        if (existing.isPresent()) {
            return existing.get();
        }
        // 2. 事务内执行（含重试）
        CommandResult result = retryTemplate.executeWithRetry(() -> transactionTemplate.execute((TransactionCallback<CommandResult>) status -> {
            OrderAggregate order = aggregateRepository.load(cmd.getAggregateId());
            action.accept(order);
            aggregateRepository.save(order);
            return CommandResult.success(order.getVersion());
        }));
        // 3. 写命令日志（同事务已提交，单独写也允许；若需严格同事务可移入上面 lambda）
        commandLogRepository.save(cmd.getCommandId(), cmd.getAggregateId(),
                cmd.getClass().getSimpleName(), result);
        return result;
    }
}
