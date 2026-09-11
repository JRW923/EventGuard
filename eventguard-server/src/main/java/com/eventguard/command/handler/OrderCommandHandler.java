package com.eventguard.command.handler;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.command.command.*;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.gateway.InventoryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OrderCommandHandler.class);

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
        // 顺序重复请求直接返回；并发请求由 execute 内的数据库事务锁串行化。
        Optional<CommandResult> existing = commandLogRepository.loadFor(cmd);
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
        // 事务外先确认库存扣减（与 reserve 对称）：把预占量转为实际扣减。
        // 失败则拒绝确认——否则订单已进 CONFIRMED 而库存没真正扣掉，后续订单会超卖。
        OrderAggregate current = aggregateRepository.load(cmd.getAggregateId());
        if (current.getReservedSkuId() != null && current.getReservedQuantity() > 0) {
            InventoryGateway.ConfirmResult r = inventoryGateway.confirm(new InventoryGateway.ConfirmRequest(
                    cmd.getAggregateId(), cmd.getCommandId(),
                    current.getReservedSkuId(), current.getReservedQuantity()));
            if (r == null || !r.success()) {
                log.warn("[库存] 确认订单扣减库存失败 order={} sku={} error={}",
                        cmd.getAggregateId(), current.getReservedSkuId(), r == null ? "无响应" : r.error());
                return CommandResult.failure(r == null ? "库存确认无响应" : r.error());
            }
        }
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
        CommandResult result = execute(cmd, order -> order.handle(cmd));
        releaseReservedInventory(cmd);
        return result;
    }

    /**
     * 取消成功后回补预留库存。CancelOrderCommand 不带 sku 信息，从聚合状态反查预留了什么。
     * <p>
     * ponytail: 库存网关调用不在数据库事务内，失败不回滚已取消的订单——取消是终态操作，
     * 不能因库存服务抖动就取消不了。release 按 commandId 幂等，泄漏的预留可对账后重放补偿。
     */
    private void releaseReservedInventory(CancelOrderCommand cmd) {
        try {
            OrderAggregate order = aggregateRepository.load(cmd.getAggregateId());
            if (order.getReservedSkuId() == null || order.getReservedQuantity() <= 0) return;
            InventoryGateway.ReleaseResult r = inventoryGateway.release(new InventoryGateway.ReleaseRequest(
                    cmd.getAggregateId(), cmd.getCommandId(),
                    order.getReservedSkuId(), order.getReservedQuantity()));
            if (r == null || !r.success()) {
                log.warn("[库存] 取消订单释放库存失败 order={} sku={} error={}",
                        cmd.getAggregateId(), order.getReservedSkuId(), r == null ? "无响应" : r.error());
            }
        } catch (Exception e) {
            log.warn("[库存] 取消订单释放库存异常 order={}", cmd.getAggregateId(), e);
        }
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
        long start = System.currentTimeMillis();
        try {
            // 事务内先按 commandId 获取数据库事务锁，再检查日志。并发相同命令因此会
            // 读取首个事务的结果，而不是在事务外预查后各自执行一次领域逻辑。
            CommandResult result = retryTemplate.executeWithRetry(() -> transactionTemplate.execute((TransactionCallback<CommandResult>) status -> {
                commandLogRepository.lock(cmd.getCommandId());
                Optional<CommandLogRepository.Entry> existing = commandLogRepository.find(cmd.getCommandId());
                String requestHash = commandLogRepository.fingerprint(cmd);
                if (existing.isPresent()) {
                    commandLogRepository.assertCompatible(existing.get(), cmd, requestHash);
                    if (metrics != null) {
                        metrics.counter("eventguard.command.total", "command", cmd.getClass().getSimpleName(),
                                "result", "idempotent");
                    }
                    return existing.get().result();
                }
                OrderAggregate order = aggregateRepository.load(cmd.getAggregateId());
                action.accept(order);
                aggregateRepository.save(order);
                CommandResult r = error == null
                        ? CommandResult.success(order.getVersion())
                        : new CommandResult(true, order.getVersion(), error, cmd.getAggregateId());
                commandLogRepository.save(cmd.getCommandId(), cmd.getAggregateId(),
                        cmd.getClass().getSimpleName(), r, requestHash);
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
