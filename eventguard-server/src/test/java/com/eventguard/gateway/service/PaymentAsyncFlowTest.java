package com.eventguard.gateway.service;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.command.CompletePaymentCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.config.GatewayProperties;
import com.eventguard.gateway.mock.MockPaymentGateway;
import com.eventguard.gateway.model.GatewayRequest;
import com.eventguard.gateway.repository.GatewayRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 步异步支付流测试：PayOrderCommand 提交后，PaymentCoordinator 发起网关支付 → 落 gateway_request(PENDING)
 * → 回调派发 CompletePaymentCommand。验证幂等（重复 initiate 不重复建单）与终态回调幂等。
 */
class PaymentAsyncFlowTest {

    private GatewayRequestRepository requestRepo;
    private OrderCommandHandler commandHandler;
    private PaymentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        requestRepo = mock(GatewayRequestRepository.class);
        commandHandler = mock(OrderCommandHandler.class);
        AggregateRepository aggregateRepository = mock(AggregateRepository.class);
        OrderAggregate order = new OrderAggregate();
        order.handle(new CreateOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "u1", new BigDecimal("99.00")));
        order.flushPendingEvents();
        when(aggregateRepository.load(any())).thenReturn(order);

        MockPaymentGateway gateway = new MockPaymentGateway(
                new GatewayProperties("mock", "mock", "mock", 0.0, 0, "SKU-A:100"));
        coordinator = new PaymentCoordinator(gateway, requestRepo,
                new GatewayCallbackService(requestRepo, commandHandler), aggregateRepository,
                new GatewayProperties("mock", "mock", "mock", 0.0, 0, "SKU-A:100"));
    }

    @Test
    void initiate_writes_pending_gateway_request_and_dispatches_complete() {
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        when(requestRepo.findByCommandId(commandId)).thenReturn(Optional.empty());
        when(requestRepo.findByExternalRef(any())).thenReturn(Optional.empty());
        when(commandHandler.handle(any(CompletePaymentCommand.class)))
                .thenReturn(CommandResult.success(2));

        PaymentCoordinator.InitiationResult init = coordinator.initiate(orderId, commandId);

        assertThat(init.failed()).isFalse();
        assertThat(init.paymentId()).isNotNull().startsWith("mockpay-");
        verify(requestRepo).insert(any(GatewayRequest.class));
        verify(commandHandler).handle(any(CompletePaymentCommand.class));
    }

    @Test
    void duplicate_initiate_for_same_command_is_idempotent() {
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        GatewayRequest existing = new GatewayRequest(
                UUID.randomUUID(), commandId, orderId, "PAYMENT", "CREATE_PAYMENT", "mock",
                "mockpay-existing", GatewayRequest.Status.PENDING, java.util.Map.of(), java.util.Map.of(),
                java.time.Instant.now(), java.time.Instant.now());
        when(requestRepo.findByCommandId(commandId)).thenReturn(Optional.of(existing));

        PaymentCoordinator.InitiationResult init = coordinator.initiate(orderId, commandId);

        // 已存在 PENDING → 不重复建单、不重复派发命令
        assertThat(init.paymentId()).isEqualTo("mockpay-existing");
        assertThat(init.failed()).isFalse();
        verify(requestRepo, org.mockito.Mockito.never()).insert(any(GatewayRequest.class));
        verify(commandHandler, org.mockito.Mockito.never()).handle(any(CompletePaymentCommand.class));
    }
}
