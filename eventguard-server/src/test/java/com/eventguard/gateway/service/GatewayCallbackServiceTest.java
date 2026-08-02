package com.eventguard.gateway.service;

import com.eventguard.command.command.CompletePaymentCommand;
import com.eventguard.command.command.FailPaymentCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.model.GatewayRequest;
import com.eventguard.gateway.repository.GatewayRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 网关回调幂等与分支测试。 */
class GatewayCallbackServiceTest {

    private GatewayRequestRepository requestRepo;
    private OrderCommandHandler commandHandler;
    private GatewayCallbackService service;

    @BeforeEach
    void setUp() {
        requestRepo = mock(GatewayRequestRepository.class);
        commandHandler = mock(OrderCommandHandler.class);
        service = new GatewayCallbackService(requestRepo, commandHandler);
    }

    @Test
    void success_callback_dispatches_complete_payment_command() {
        String externalRef = "mockpay-1";
        UUID orderId = UUID.randomUUID();
        when(requestRepo.findByExternalRef(externalRef)).thenReturn(Optional.of(
                pending(externalRef, orderId)));
        when(commandHandler.handle(any(CompletePaymentCommand.class)))
                .thenReturn(CommandResult.success(2));

        service.process(externalRef, orderId, true, null);

        ArgumentCaptor<CompletePaymentCommand> cap = ArgumentCaptor.forClass(CompletePaymentCommand.class);
        verify(commandHandler).handle(cap.capture());
        assertThat(cap.getValue().getAggregateId()).isEqualTo(orderId);
        assertThat(cap.getValue().paymentId()).isEqualTo(externalRef);
        verify(requestRepo).updateStatus(any(), any(), any());
    }

    @Test
    void failure_callback_dispatches_fail_payment_command() {
        String externalRef = "mockpay-2";
        UUID orderId = UUID.randomUUID();
        when(requestRepo.findByExternalRef(externalRef)).thenReturn(Optional.of(
                pending(externalRef, orderId)));
        when(commandHandler.handle(any(FailPaymentCommand.class)))
                .thenReturn(CommandResult.success(2));

        service.process(externalRef, orderId, false, "余额不足");

        ArgumentCaptor<FailPaymentCommand> cap = ArgumentCaptor.forClass(FailPaymentCommand.class);
        verify(commandHandler).handle(cap.capture());
        assertThat(cap.getValue().getAggregateId()).isEqualTo(orderId);
        assertThat(cap.getValue().reason()).isEqualTo("余额不足");
        verify(requestRepo).updateStatus(any(), any(), any());
    }

    @Test
    void terminal_callback_is_idempotent_and_skips_dispatch() {
        String externalRef = "mockpay-3";
        UUID orderId = UUID.randomUUID();
        GatewayRequest terminal = new GatewayRequest(UUID.randomUUID(), UUID.randomUUID(), orderId,
                "PAYMENT", "CREATE_PAYMENT", "mock", externalRef, GatewayRequest.Status.SUCCEEDED,
                Map.of(), Map.of(), Instant.now(), Instant.now());
        when(requestRepo.findByExternalRef(externalRef)).thenReturn(Optional.of(terminal));

        service.process(externalRef, orderId, true, null);

        // 已终态（SUCCEEDED）→ 不再重复派发命令
        verify(commandHandler, never()).handle(any(CompletePaymentCommand.class));
        verify(commandHandler, never()).handle(any(FailPaymentCommand.class));
        verify(requestRepo, never()).updateStatus(any(), any(), any());
    }

    private static GatewayRequest pending(String externalRef, UUID orderId) {
        return new GatewayRequest(UUID.randomUUID(), UUID.randomUUID(), orderId,
                "PAYMENT", "CREATE_PAYMENT", "mock", externalRef, GatewayRequest.Status.PENDING,
                Map.of(), Map.of(), Instant.now(), Instant.now());
    }
}
