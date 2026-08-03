package com.eventguard.command.handler;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.command.command.*;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.gateway.InventoryGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 订单命令处理器：所有订单命令统一走「幂等检查 → 事务内加载+处理+保存 → 写命令日志」。
 * 重试由 CommandRetryTemplate 包装，每次重试开启新事务。
 * <p>
 * B 步：ReserveInventoryCommand 在幂等检查后先调 InventoryGateway（幂等键=commandId），
 * 按结果 raise InventoryReservedEvent（成功）或 InventoryReservationFailedEvent（库存不足）。
 */
@Service
public class OrderCommandHandler {

    private final AggregateRepository aggregateRepository;
    private final CommandLogRepository commandLogRepository;
    private final CommandRetryTemplate retryTemplate;
    private final TransactionTemplate transactionTemplate;
    private final InventoryGateway inventoryGateway;

    // ponytail: 可观测指标为可选注入（EventGuardMetrics 是 @Component；单测 new 直构时为 null 走空操作）
    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public OrderCommandHandler(AggregateRepository aggregateRepository,
                               CommandLogRepository commandLogRepository,
                               CommandRetryTemplate retryTemplate,
                               PlatformTransactionManager transactionManager,
                               InventoryGateway inventoryGateway) {
        this.aggregateRepository = aggregateRepository;
        this.commandLogRepository = commandLogRepository;
        this.retryTemplate = retryTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.inventoryGateway = inventoryGateway;
    }

    public CommandResult handle(CreateOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(PayOrderCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(CompletePaymentCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(FailPaymentCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(RetryPaymentCommand cmd) {
        return execute(cmd, order -> order.handle(cmd));
    }

    public CommandResult handle(ReserveInventoryCommand cmd) {
        // 幂等先行（避免重放/重试重复触发网关副作用）
        Optional<CommandResult> existing = commandLogRepository.loadResult(cmd.getCommandId());
        if (existing.isPresent()) {
            return existing.get();
        }
        // 先调库存网关（幂等键=commandId），按结果决定 raise 哪个事件
        InventoryGateway.ReservationResult res = inventoryGateway.reserve(
                new InventoryGateway.ReserveRequest(cmd.getAggregateId(), cmd.getCommandId(),
                        cmd.skuId(), cmd.quantity()));
        if (res.success()) {
            return execute(cmd, order -> order.handle(cmd));
        }
        // 库存不足：raise InventoryReservationFailedEvent（状态不变），返回带失败信息的成功结果
        return execute(cmd, order -> order.handleInventoryReservationFailed(cmd, res.error()), res.error());
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
     * 通用执行模板：幂等检查 + 事务内加载/处理/保存事件 + 写命令日志（同事务，保证原子性）。
     */
    private CommandResult execute(Command cmd, Consumer<OrderAggregate> action) {
        return execute(cmd, action, null);
    }

    /**
     * 带失败信息的执行模板：命令本身成功（事件已落库），但需向调用方返回失败原因
     * （如库存不足 reserve 失败）。command_log 记录相同结果，保证幂等回放一致。
     */
    private CommandResult execute(Command cmd, Consumer<OrderAggregate> action, String error) {
        // 1. 幂等检查（事务外）
        Optional<CommandResult> existing = commandLogRepository.loadResult(cmd.getCommandId());
        if (existing.isPresent()) {
            if (metrics != null) {
                metrics.counter("eventguard.command.total", "command", cmd.getClass().getSimpleName(),
                        "result", "idempotent");
            }
            return existing.get();
        }
        long start = System.currentTimeMillis();
        try {
            // 2. 事务内执行（含重试）：加载/处理/保存事件 + 写命令日志（同事务，保证原子性）
            CommandResult result = retryTemplate.executeWithRetry(() -> transactionTemplate.execute((TransactionCallback<CommandResult>) status -> {
                OrderAggregate order = aggregateRepository.load(cmd.getAggregateId());
                action.accept(order);
                aggregateRepository.save(order);
                CommandResult r = error == null
                        ? CommandResult.success(order.getVersion())
                        : new CommandResult(true, order.getVersion(), error, cmd.getAggregateId());
                commandLogRepository.save(cmd.getCommandId(), cmd.getAggregateId(),
                        cmd.getClass().getSimpleName(), r);
                return r;
            }));
            if (metrics != null) {
                metrics.counter("eventguard.command.total", "command", cmd.getClass().getSimpleName(),
                        "result", "success");
            }
            return result;
        } catch (Exception e) {
            if (metrics != null) {
                metrics.counter("eventguard.command.total", "command", cmd.getClass().getSimpleName(),
                        "result", "failure");
            }
            throw e;
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.command.duration", System.currentTimeMillis() - start,
                        "command", cmd.getClass().getSimpleName());
            }
        }
    }
}
