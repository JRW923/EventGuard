package com.eventguard.compensation.saga;

import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import com.eventguard.compensation.repository.ApprovalRepository;
import com.eventguard.compensation.service.CompensationService;
import com.eventguard.common.metrics.EventGuardMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 补偿 Saga 编排器（对齐设计文档 7.4）：按步骤执行补偿动作，遇 requiresApproval 挂起等审批。
 * <p>
 * ponytail: saga 实例保存在内存 Map（单实例上限）；审批请求持久化到 compensation_approval 表，
 * 审批后按 sagaId 恢复继续执行。MVP 不做多实例 saga 存储，升级路径=落库 saga 状态机。
 */
@Component
public class CompensationSaga {

    private static final Logger log = LoggerFactory.getLogger(CompensationSaga.class);

    private final CompensationService compensationService;
    private final CompensationActionRegistry registry;
    private final ApprovalRepository approvalRepository;
    private final Map<UUID, SagaInstance> instances = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public CompensationSaga(CompensationService compensationService,
                            CompensationActionRegistry registry,
                            ApprovalRepository approvalRepository) {
        this.compensationService = compensationService;
        this.registry = registry;
        this.approvalRepository = approvalRepository;
    }

    /** 内存中的 saga 实例状态。 */
    static class SagaInstance {
        final UUID sagaId;
        final UUID aggregateId;
        final List<SagaStep> steps;
        int index;
        SagaStatus status = SagaStatus.STARTED;

        SagaInstance(UUID sagaId, UUID aggregateId, List<SagaStep> steps) {
            this.sagaId = sagaId;
            this.aggregateId = aggregateId;
            this.steps = new ArrayList<>(steps);
        }
    }

    /**
     * 启动一个新的补偿 saga：执行到第一个需审批的步骤为止（含）。
     *
     * @return saga 状态
     */
    public SagaStatus start(UUID aggregateId, List<SagaStep> steps) {
        return begin(aggregateId, steps).status;
    }

    /** 启动并返回 sagaId（测试/审计用；SagaTrigger 走 {@link #start}）。 */
    SagaInstance begin(UUID aggregateId, List<SagaStep> steps) {
        SagaInstance saga = new SagaInstance(UUID.randomUUID(), aggregateId, steps);
        instances.put(saga.sagaId, saga);
        run(saga);
        return saga;
    }

    /** 从当前 index 起执行；遇到需审批的步骤挂起并落审批单。 */
    private SagaStatus run(SagaInstance saga) {
        saga.status = SagaStatus.EXECUTING;
        while (saga.index < saga.steps.size()) {
            SagaStep step = saga.steps.get(saga.index);
            if (registry.get(step.actionType()) != null
                    && registry.get(step.actionType()).requiresApproval(saga.aggregateId, step.params())) {
                // 挂起等审批：落审批单，返回等待
                UUID approvalId = UUID.randomUUID();
                approvalRepository.insert(approvalId, saga.sagaId, step.actionType(), saga.aggregateId,
                        step.params(), "saga");
                saga.status = SagaStatus.AWAITING_APPROVAL;
                if (metrics != null) {
                    metrics.counter("eventguard.saga.final_status", "status", "AWAITING_APPROVAL");
                }
                log.info("[Saga] {} 步骤 {} 需审批，挂起 approvalId={}", saga.sagaId, step.actionType(), approvalId);
                return saga.status;
            }
            executeStep(saga, step);
            saga.index++;
        }
        saga.status = SagaStatus.COMPLETED;
        instances.remove(saga.sagaId);
        if (metrics != null) {
            metrics.counter("eventguard.saga.final_status", "status", "COMPLETED");
        }
        log.info("[Saga] {} 已完成", saga.sagaId);
        return saga.status;
    }

    private void executeStep(SagaInstance saga, SagaStep step) {
        long start = System.currentTimeMillis();
        try {
            CompensationResult result = compensationService.execute(new CompensationRequest(
                    step.actionType(), saga.aggregateId, step.params()));
            log.info("[Saga] 步骤 {} 执行结果 success={} {}", step.actionType(), result.isSuccess(), result.getMessage());
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.saga.step.duration", System.currentTimeMillis() - start,
                        "action", step.actionType());
            }
        }
    }

    /**
     * 审批回调：approved 则执行该审批步骤并继续后续；rejected 则标记 FAILED。
     */
    public SagaStatus onApproved(UUID approvalId, boolean approved, String decidedBy) {
        Optional<ApprovalRepository.Approval> approvalOpt = approvalRepository.findByApprovalId(approvalId);
        if (approvalOpt.isEmpty()) {
            log.warn("[Saga] 审批单不存在 approvalId={}", approvalId);
            return SagaStatus.FAILED;
        }
        ApprovalRepository.Approval approval = approvalOpt.get();
        if (!"PENDING".equals(approval.status())) {
            log.warn("[Saga] 审批单已处理过 approvalId={} status={}", approvalId, approval.status());
            return SagaStatus.FAILED;
        }
        approvalRepository.decide(approvalId, approved ? "APPROVED" : "REJECTED", decidedBy);

        SagaInstance saga = instances.get(approval.sagaId());
        if (saga == null) {
            log.warn("[Saga] saga 实例不存在 sagaId={}（可能已被清理）", approval.sagaId());
            return SagaStatus.FAILED;
        }
        if (!approved) {
            saga.status = SagaStatus.FAILED;
            instances.remove(saga.sagaId);
            if (metrics != null) {
                metrics.counter("eventguard.saga.final_status", "status", "FAILED");
            }
            log.info("[Saga] {} 被拒绝，标记 FAILED", saga.sagaId);
            return saga.status;
        }
        // 已审批通过：该步骤执行，index 前进后继续
        SagaStep step = saga.steps.get(saga.index);
        executeStep(saga, step);
        saga.index++;
        return run(saga);
    }

    public SagaStatus status(UUID sagaId) {
        SagaInstance s = instances.get(sagaId);
        return s != null ? s.status : SagaStatus.COMPLETED;
    }

    public Map<UUID, SagaStatus> allStatuses() {
        Map<UUID, SagaStatus> m = new ConcurrentHashMap<>();
        instances.forEach((k, v) -> m.put(k, v.status));
        return m;
    }
}
