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
import java.util.HashMap;
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
 * <p>
 * 崩溃恢复：审批落单时把「剩余步骤」写进审批单 params 的保留键 {@link #SAGA_STEPS_KEY}，
 * server 重启后由 {@code SagaRecoveryRunner} 用 {@link #recoverPending} 重建内存实例，
 * 审批通过仍能继续执行——解决「审批单在、实例丢，重启后审批即 FAILED」的补偿中断。
 */
@Component
public class CompensationSaga {

    private static final Logger log = LoggerFactory.getLogger(CompensationSaga.class);

    /** 审批单 params 中保存剩余步骤的保留键（带 __ 前缀，前端视图会过滤）。 */
    public static final String SAGA_STEPS_KEY = "__saga_remaining_steps";

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
                // 挂起等审批：落审批单，返回等待。审批单 params 附上剩余步骤，供重启后恢复内存实例。
                UUID approvalId = UUID.randomUUID();
                Map<String, Object> approvalParams = new HashMap<>(step.params() != null ? step.params() : Map.of());
                approvalParams.put(SAGA_STEPS_KEY, remainingStepsJson(saga));
                approvalRepository.insert(approvalId, saga.sagaId, step.actionType(), saga.aggregateId,
                        approvalParams, "saga");
                saga.status = SagaStatus.AWAITING_APPROVAL;
                if (metrics != null) {
                    metrics.counter("eventguard.saga.final_status", "status", "AWAITING_APPROVAL");
                }
                log.info("[Saga] {} 步骤 {} 需审批，挂起 approvalId={}", saga.sagaId, step.actionType(), approvalId);
                return saga.status;
            }
            if (!executeStep(saga, step)) return fail(saga);
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

    private boolean executeStep(SagaInstance saga, SagaStep step) {
        long start = System.currentTimeMillis();
        try {
            CompensationResult result = compensationService.execute(new CompensationRequest(
                    step.actionType(), saga.aggregateId, step.params()));
            log.info("[Saga] 步骤 {} 执行结果 success={} {}", step.actionType(), result.isSuccess(), result.getMessage());
            return result.isSuccess();
        } catch (Exception e) {
            log.error("[Saga] 步骤 {} 执行异常", step.actionType(), e);
            return false;
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.saga.step.duration", System.currentTimeMillis() - start,
                        "action", step.actionType());
            }
        }
    }

    private SagaStatus fail(SagaInstance saga) {
        saga.status = SagaStatus.FAILED;
        instances.remove(saga.sagaId);
        if (metrics != null) metrics.counter("eventguard.saga.final_status", "status", "FAILED");
        return saga.status;
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
        if (!approvalRepository.decide(approvalId, approved ? "APPROVED" : "REJECTED", decidedBy)) {
            log.warn("[Saga] 审批单已被其他请求处理 approvalId={}", approvalId);
            return SagaStatus.FAILED;
        }

        SagaInstance saga = instances.get(approval.sagaId());
        if (saga == null) {
            log.warn("[Saga] saga 实例不存在 sagaId={}（可能已被清理）", approval.sagaId());
            return SagaStatus.FAILED;
        }
        if (!approved) {
            fail(saga);
            log.info("[Saga] {} 被拒绝，标记 FAILED", saga.sagaId);
            return saga.status;
        }
        // 已审批通过：该步骤执行，index 前进后继续
        SagaStep step = saga.steps.get(saga.index);
        if (!executeStep(saga, step)) return fail(saga);
        saga.index++;
        return run(saga);
    }

    /** 把 saga 剩余步骤（含待审批步骤）序列化为可 JSON 化的 List<Map>，存进审批单 params。 */
    private List<Map<String, Object>> remainingStepsJson(SagaInstance saga) {
        List<Map<String, Object>> json = new ArrayList<>();
        for (SagaStep s : saga.steps.subList(saga.index, saga.steps.size())) {
            json.add(Map.of("actionType", s.actionType(), "params", s.params()));
        }
        return json;
    }

    /**
     * 启动恢复：从 PENDING 审批单重建内存实例（剩余步骤读取 params 保留键 {@link #SAGA_STEPS_KEY}）。
     * <p>
     * 恢复后 index=0 即待审批步骤；审批通过时 {@link #onApproved} 继续执行，不因重启而中断补偿。
     */
    public void recoverPending(ApprovalRepository.Approval approval) {
        Object raw = approval.params().get(SAGA_STEPS_KEY);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("[Saga] 审批单缺少剩余步骤信息，无法恢复 sagaId={}", approval.sagaId());
            return;
        }
        List<SagaStep> steps = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object actionType = m.get("actionType");
            Object params = m.get("params");
            if (actionType instanceof String at) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : Map.of();
                steps.add(new SagaStep(at, p));
            }
        }
        SagaInstance saga = new SagaInstance(approval.sagaId(), approval.aggregateId(), steps);
        saga.index = 0; // 剩余步骤从待审批步骤开始
        saga.status = SagaStatus.AWAITING_APPROVAL;
        instances.put(saga.sagaId, saga);
        log.info("[Saga] 启动恢复 sagaId={} aggregateId={}（剩余 {} 步，待审批 {}）",
                saga.sagaId, saga.aggregateId, steps.size(), approval.actionType());
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
