package com.eventguard.command.handler;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.command.aggregate.OrderStatus;
import com.eventguard.command.command.CompletePaymentCommand;
import com.eventguard.command.command.ConfirmOrderCommand;
import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.command.PayOrderCommand;
import com.eventguard.command.command.ReserveInventoryCommand;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.mock.MockInventoryGateway;
import com.eventguard.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandHandlerTest {

    @Mock AggregateRepository aggregateRepository;
    @Mock CommandLogRepository commandLogRepository;
    @Mock CommandRetryTemplate retryTemplate;
    @Mock PlatformTransactionManager transactionManager;

    OrderCommandHandler handler;
    MockInventoryGateway inventory;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // handler 内部 new TransactionTemplate(transactionManager)；
        // mock 的 transactionManager 默认 getTransaction→null、commit/rollback→no-op，
        // 因此 TransactionTemplate.execute 会直接执行 callback，无需额外 stub。
        inventory = new MockInventoryGateway(
                new GatewayProperties("mock", "mock", "mock", 0.0, 0, "SKU-A:100"));
        handler = new OrderCommandHandler(aggregateRepository, commandLogRepository, retryTemplate, transactionManager,
                inventory);
        lenient().when(commandLogRepository.find(any())).thenReturn(Optional.empty());
    }

    @Test
    void createOrder_should_return_success_and_save_command_log() {
        UUID orderId = UUID.randomUUID();
        when(aggregateRepository.load(orderId)).thenReturn(new OrderAggregate());
        when(retryTemplate.executeWithRetry(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<CommandResult> s = inv.getArgument(0);
            return s.get();
        });

        CreateOrderCommand cmd = new CreateOrderCommand(UUID.randomUUID(), orderId, "u1", new BigDecimal("99"));
        CommandResult result = handler.handle(cmd);

        assertThat(result.success()).isTrue();
        verify(commandLogRepository).save(eq(cmd.getCommandId()), eq(orderId), eq("CreateOrderCommand"), any(), any());
    }

    @Test
    void duplicate_commandId_should_return_previous_result() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        CommandResult previous = CommandResult.success(1);
        CreateOrderCommand cmd = new CreateOrderCommand(commandId, orderId, "u1", new BigDecimal("99"));
        CommandLogRepository.Entry entry = new CommandLogRepository.Entry(
                commandId, orderId, "CreateOrderCommand", commandLogRepository.fingerprint(cmd), previous);
        when(commandLogRepository.find(commandId)).thenReturn(Optional.of(entry));
        when(retryTemplate.executeWithRetry(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<CommandResult> supplier = inv.getArgument(0);
            return supplier.get();
        });

        CommandResult result = handler.handle(cmd);

        assertThat(result).isEqualTo(previous);
        verify(aggregateRepository, never()).load(any());
        verify(aggregateRepository, never()).save(any());
    }

    @Test
    void payOrder_should_succeed_when_status_is_pending_payment() {
        UUID orderId = UUID.randomUUID();
        OrderAggregate agg = new OrderAggregate();
        agg.handle(new CreateOrderCommand(UUID.randomUUID(), orderId, "u1", new BigDecimal("99")));
        agg.flushPendingEvents();
        when(aggregateRepository.load(orderId)).thenReturn(agg);
        when(retryTemplate.executeWithRetry(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<CommandResult> s = inv.getArgument(0);
            return s.get();
        });
        PayOrderCommand cmd = new PayOrderCommand(UUID.randomUUID(), orderId, "pay-1");
        CommandResult result = handler.handle(cmd);

        // B 步：pay 只记录支付意图，状态仍 PENDING_PAYMENT，等待网关回调 CompletePaymentCommand
        assertThat(result.success()).isTrue();
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** 走通 创建→支付→预留→确认，供确认相关用例复用。 */
    private OrderAggregate orderReadyToConfirm(UUID orderId, String sku, int qty) {
        OrderAggregate agg = new OrderAggregate();
        when(aggregateRepository.load(orderId)).thenReturn(agg);
        when(retryTemplate.executeWithRetry(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<CommandResult> s = inv.getArgument(0);
            return s.get();
        });
        handler.handle(new CreateOrderCommand(UUID.randomUUID(), orderId, "u1", new BigDecimal("99")));
        handler.handle(new CompletePaymentCommand(UUID.randomUUID(), orderId, "pay-1"));
        handler.handle(new ReserveInventoryCommand(UUID.randomUUID(), orderId, sku, qty));
        return agg;
    }

    @Test
    void confirmOrder_should_turn_reserved_into_actual_deduction() {
        UUID orderId = UUID.randomUUID();
        orderReadyToConfirm(orderId, "SKU-A", 30);

        CommandResult result = handler.handle(new ConfirmOrderCommand(UUID.randomUUID(), orderId));

        assertThat(result.success()).isTrue();
        // 确认后预占转实扣：物理库存真正减少，预占清零，可售不变
        assertThat(inventory.snapshot().get("SKU-A").total()).isEqualTo(70);
        assertThat(inventory.snapshot().get("SKU-A").reserved()).isZero();
        assertThat(inventory.currentStock("SKU-A")).isEqualTo(70);
    }

    @Test
    void confirmOrder_should_be_rejected_when_reservation_already_released() {
        UUID orderId = UUID.randomUUID();
        orderReadyToConfirm(orderId, "SKU-A", 30);
        // 模拟预占已被释放（如重复释放），此时确认会导致超卖，必须拒绝
        inventory.release(new InventoryGateway.ReleaseRequest(
                orderId, UUID.randomUUID(), "SKU-A", 30));

        CommandResult result = handler.handle(new ConfirmOrderCommand(UUID.randomUUID(), orderId));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("预留不足");
        assertThat(inventory.snapshot().get("SKU-A").total()).isEqualTo(100); // 库存未被扣
    }
}
