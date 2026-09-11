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
import static org.mockito.Mockito.never;
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
        when(approvalRepository.decide(approvalId, "APPROVED", "operator")).thenReturn(true);

        SagaStatus resumed = saga.onApproved(approvalId, true, "operator");

        assertThat(resumed).isEqualTo(SagaStatus.COMPLETED);
        verify(compensationService).execute(any(CompensationRequest.class));
    }

    @Test
    void approval_insert_stores_remaining_steps_for_recovery() {
        when(registry.get("RISKY")).thenReturn(action(true));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        saga.begin(UUID.randomUUID(), List.of(
                new SagaStep("RISKY", Map.of("amount", BigDecimal.valueOf(200))),
                new SagaStep("NOTIFY", Map.of())));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(approvalRepository).insert(any(), any(), eq("RISKY"), any(), paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue()).containsKey(CompensationSaga.SAGA_STEPS_KEY);
    }

    @Test
    void recovery_rebuilds_instance_and_approve_continues() {
        // 模拟「重启前落库的 PENDING 审批单」：params 带剩余步骤（待审批步骤 + 后续步骤）
        when(registry.get("RISKY")).thenReturn(action(true));
        when(registry.get("NOTIFY")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        UUID aggregateId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        Map<String, Object> params = new java.util.HashMap<>();
        params.put(CompensationSaga.SAGA_STEPS_KEY, List.of(
                Map.of("actionType", "RISKY", "params", Map.of("amount", BigDecimal.valueOf(200))),
                Map.of("actionType", "NOTIFY", "params", Map.of())));
        ApprovalRepository.Approval approval = new ApprovalRepository.Approval(
                approvalId, sagaId, "RISKY", aggregateId, params, "PENDING", "saga", Instant.now(), null, null);
        when(approvalRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(approval));
        when(approvalRepository.decide(approvalId, "APPROVED", "operator")).thenReturn(true);

        // 启动恢复 → 实例回到内存，状态 AWAITING_APPROVAL
        saga.recoverPending(approval);
        assertThat(saga.status(sagaId)).isEqualTo(SagaStatus.AWAITING_APPROVAL);

        // 审批通过 → 执行 RISKY + 继续 NOTIFY → COMPLETED（不再因实例丢失而 FAILED）
        SagaStatus resumed = saga.onApproved(approvalId, true, "operator");
        assertThat(resumed).isEqualTo(SagaStatus.COMPLETED);
        verify(compensationService, times(2)).execute(any(CompensationRequest.class));
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
    void failed_step_stops_saga_without_running_following_steps() {
        when(registry.get("FAIL")).thenReturn(action(false));
        when(registry.get("NEXT")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.failure("gateway unavailable"));

        SagaStatus status = saga.start(UUID.randomUUID(), List.of(
                new SagaStep("FAIL", Map.of()), new SagaStep("NEXT", Map.of())));

        assertThat(status).isEqualTo(SagaStatus.FAILED);
        verify(compensationService, times(1)).execute(any(CompensationRequest.class));
    }

    @Test
    void dependent_step_runs_after_its_dependency_regardless_of_array_order() {
        when(registry.get("FIRST")).thenReturn(action(false));
        when(registry.get("SECOND")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        // 故意把被依赖的步骤写在后面：依赖声明生效的话 FIRST 仍先执行
        SagaStatus status = saga.start(UUID.randomUUID(), List.of(
                new SagaStep("second", "SECOND", Map.of(), "first"),
                new SagaStep("first", "FIRST", Map.of())));

        assertThat(status).isEqualTo(SagaStatus.COMPLETED);
        ArgumentCaptor<CompensationRequest> captor = ArgumentCaptor.forClass(CompensationRequest.class);
        verify(compensationService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(0).getActionType()).isEqualTo("FIRST");
        assertThat(captor.getAllValues().get(1).getActionType()).isEqualTo("SECOND");
    }

    @Test
    void approval_does_not_block_independent_steps() {
        when(registry.get("RISKY")).thenReturn(action(true));
        when(registry.get("NOTIFY")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        SagaStatus status = saga.start(UUID.randomUUID(), List.of(
                new SagaStep("risky", "RISKY", Map.of("amount", BigDecimal.valueOf(200))),
                new SagaStep("notify", "NOTIFY", Map.of())));

        assertThat(status).isEqualTo(SagaStatus.AWAITING_APPROVAL);
        // 核心收益：退款挂起等人工审批时，无依赖的延迟通知照常发出
        verify(compensationService, times(1)).execute(any(CompensationRequest.class));
        verify(approvalRepository).insert(any(), any(), eq("RISKY"), any(), any(), any());
    }

    @Test
    void step_depending_on_pending_approval_waits_until_approved() {
        when(registry.get("RISKY")).thenReturn(action(true));
        when(registry.get("AFTER")).thenReturn(action(false));
        when(compensationService.execute(any(CompensationRequest.class)))
                .thenReturn(CompensationResult.success("ok"));

        UUID aggregateId = UUID.randomUUID();
        CompensationSaga.SagaInstance instance = saga.begin(aggregateId, List.of(
                new SagaStep("risky", "RISKY", Map.of("amount", BigDecimal.valueOf(200))),
                new SagaStep("after", "AFTER", Map.of(), "risky")));

        assertThat(instance.status).isEqualTo(SagaStatus.AWAITING_APPROVAL);
        verify(compensationService, never()).execute(any(CompensationRequest.class));

        // 审批通过 → RISKY 执行 → AFTER 依赖满足 → 执行 → COMPLETED
        UUID approvalId = UUID.randomUUID();
        Map<String, Object> params = new java.util.HashMap<>();
        params.put(CompensationSaga.SAGA_STEP_ID_KEY, "risky");
        params.put(CompensationSaga.SAGA_STEPS_KEY, List.of(
                Map.of("id", "risky", "actionType", "RISKY",
                        "params", Map.of("amount", BigDecimal.valueOf(200)), "dependsOn", List.of()),
                Map.of("id", "after", "actionType", "AFTER",
                        "params", Map.of(), "dependsOn", List.of("risky"))));
        ApprovalRepository.Approval approval = new ApprovalRepository.Approval(
                approvalId, instance.sagaId, "RISKY", aggregateId, params, "PENDING", "saga",
                Instant.now(), null, null);
        when(approvalRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(approval));
        when(approvalRepository.decide(approvalId, "APPROVED", "operator")).thenReturn(true);

        SagaStatus resumed = saga.onApproved(approvalId, true, "operator");

        assertThat(resumed).isEqualTo(SagaStatus.COMPLETED);
        verify(compensationService, times(2)).execute(any(CompensationRequest.class));
    }

    @Test
    void circular_dependency_fails_saga_without_executing_anything() {
        when(registry.get("A")).thenReturn(action(false));
        when(registry.get("B")).thenReturn(action(false));

        SagaStatus status = saga.start(UUID.randomUUID(), List.of(
                new SagaStep("a", "A", Map.of(), "b"),
                new SagaStep("b", "B", Map.of(), "a")));

        assertThat(status).isEqualTo(SagaStatus.FAILED);
        verify(compensationService, never()).execute(any(CompensationRequest.class));
    }

    @Test
    void dependency_on_unknown_step_fails_saga() {
        when(registry.get("A")).thenReturn(action(false));

        SagaStatus status = saga.start(UUID.randomUUID(), List.of(
                new SagaStep("a", "A", Map.of(), "nonexistent")));

        assertThat(status).isEqualTo(SagaStatus.FAILED);
        verify(compensationService, never()).execute(any(CompensationRequest.class));
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
