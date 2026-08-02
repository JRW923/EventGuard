package com.eventguard.compensation.saga;

import com.eventguard.compensation.action.CompensationAction;
import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import com.eventguard.compensation.repository.ApprovalRepository;
import com.eventguard.compensation.service.CompensationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 补偿 Saga 编排测试：全自动执行 / 高风险挂起审批 / 审批后继续。 */
class CompensationSagaTest {

    private final CompensationService compensationService = mock(CompensationService.class);
    private final CompensationActionRegistry registry = mock(CompensationActionRegistry.class);
    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final CompensationSaga saga = new CompensationSaga(compensationService, registry, approvalRepository);

    private static CompensationAction action(boolean needsApproval) {
        return new CompensationAction() {
            @Override public String actionType() { return "X"; }
            @Override public String defaultRiskLevel() { return "LOW"; }
            @Override public boolean requiresApproval(UUID aggregateId, Map<String, Object> params) {
                return needsApproval;
            }
            @Override public String execute(UUID aggregateId, Map<String, Object> params) { return "done"; }
        };
    }

    @Test
    void all_auto_steps_complete_without_approval() {
        when(registry.get("AUTO")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        SagaStatus status = saga.start(UUID.randomUUID(), List.of(new SagaStep("AUTO", Map.of())));

        assertThat(status).isEqualTo(SagaStatus.COMPLETED);
        verify(compensationService).execute(any(CompensationRequest.class));
        verify(approvalRepository, times(0)).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void approval_step_suspends_saga_and_resumes_after_approve() {
        when(registry.get("RISKY")).thenReturn(action(true));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        UUID aggregateId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();

        // start → 挂起 AWAITING_APPROVAL，落审批单
        CompensationSaga.SagaInstance instance = saga.begin(aggregateId, List.of(
                new SagaStep("RISKY", Map.of("amount", BigDecimal.valueOf(200)))));
        UUID sagaId = instance.sagaId;

        assertThat(instance.status).isEqualTo(SagaStatus.AWAITING_APPROVAL);
        verify(approvalRepository).insert(any(UUID.class), eq(sagaId), any(), any(), any(), any());
        verify(compensationService, times(0)).execute(any(CompensationRequest.class));

        // 审批通过 → 恢复执行该步骤 → COMPLETED
        ApprovalRepository.Approval approval = new ApprovalRepository.Approval(
                approvalId, sagaId, "RISKY", aggregateId, Map.of("amount", BigDecimal.valueOf(200)),
                "PENDING", "saga", Instant.now(), null, null);
        when(approvalRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(approval));

        SagaStatus resumed = saga.onApproved(approvalId, true, "operator");

        assertThat(resumed).isEqualTo(SagaStatus.COMPLETED);
        verify(compensationService).execute(any(CompensationRequest.class));
    }

    @Test
    void unknown_approval_returns_failed() {
        when(approvalRepository.findByApprovalId(any())).thenReturn(Optional.empty());
        assertThat(saga.onApproved(UUID.randomUUID(), true, "operator")).isEqualTo(SagaStatus.FAILED);
    }

    @Test
    void already_decided_approval_returns_failed() {
        ApprovalRepository.Approval approval = new ApprovalRepository.Approval(
                UUID.randomUUID(), UUID.randomUUID(), "X", UUID.randomUUID(), Map.of(),
                "APPROVED", "saga", Instant.now(), Instant.now(), "operator");
        when(approvalRepository.findByApprovalId(any())).thenReturn(Optional.of(approval));
        assertThat(saga.onApproved(UUID.randomUUID(), true, "operator")).isEqualTo(SagaStatus.FAILED);
    }

    @Test
    void refund_action_requires_approval_for_amount_over_100() {
        // 用真实 RefundAction 验证审批规则
        com.eventguard.gateway.mock.MockPaymentGateway gw =
                new com.eventguard.gateway.mock.MockPaymentGateway(
                        new com.eventguard.gateway.config.GatewayProperties("mock", "mock", "mock", 0.0, 0, ""));
        com.eventguard.compensation.action.RefundAction refund = new com.eventguard.compensation.action.RefundAction(gw);

        assertThat(refund.requiresApproval(UUID.randomUUID(), Map.of("amount", BigDecimal.valueOf(50)))).isFalse();
        assertThat(refund.requiresApproval(UUID.randomUUID(), Map.of("amount", BigDecimal.valueOf(101)))).isTrue();
        assertThat(refund.requiresApproval(UUID.randomUUID(), Map.of())).isFalse();
    }
}
