package com.eventguard.compensation.saga;

import com.eventguard.compensation.action.CompensationAction;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 补偿 Saga 编排器（对齐设计文档 7.4）：按步骤的 dependsOn 依赖图调度执行，遇 requiresApproval 挂起等审批。
 * <p>
 * 依赖由 {@link SagaStep#dependsOn()} 显式声明，编排器据此判断哪些步骤可发射，
 * 数组顺序不再承载语义——重排步骤列表不会改变执行结果。
 * <p>
 * ponytail: 单线程顺序发射，不引入线程池。真并行要解决事务边界与连接池占用（投影池仅 2 连接），
 * 当前步骤规模（2~3 步）串行足够。依赖声明的收益是「显式化依赖 + 审批不阻塞无依赖步骤」，不是吞吐。
 * <p>
 * ponytail: saga 实例保存在内存 Map（单实例上限）；审批请求持久化到 compensation_approval 表，
 * 审批后按 sagaId 恢复继续执行。MVP 不做多实例 saga 存储，升级路径=落库 saga 状态机。
 * <p>
 * 崩溃恢复：审批落单时把「未完成步骤」与「该审批对应的步骤 id」写进审批单 params 的保留键，
 * server 重启后由 {@code SagaRecoveryRunner} 用 {@link #recoverPending} 重建内存实例，
 * 审批通过仍能继续执行——解决「审批单在、实例丢，重启后审批即 FAILED」的补偿中断。
 */
@Component
public class CompensationSaga {

    private static final Logger log = LoggerFactory.getLogger(CompensationSaga.class);

    /** 审批单 params 中保存未完成步骤的保留键（带 __ 前缀，前端视图会过滤）。 */
    public static final String SAGA_STEPS_KEY = "__saga_remaining_steps";
    /** 审批单 params 中保存该审批对应步骤 id 的保留键（同类型步骤可出现多次，故存 id 而非 actionType）。 */
    public static final String SAGA_STEP_ID_KEY = "__saga_step_id";

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
        /** 已成功执行的步骤 id。 */
        final Set<String> done = new HashSet<>();
        /** 已挂起等审批的步骤 id。 */
        final Set<String> awaitingApproval = new HashSet<>();
        SagaStatus status = SagaStatus.STARTED;

        SagaInstance(UUID sagaId, UUID aggregateId, List<SagaStep> steps) {
            this.sagaId = sagaId;
            this.aggregateId = aggregateId;
            this.steps = new ArrayList<>(steps);
        }
    }

    /**
     * 启动一个新的补偿 saga：按依赖图执行，需审批的步骤挂起。
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

    /**
     * 按依赖图调度：反复扫描「依赖已满足且未执行」的步骤执行，直到没有进展。
     * <p>
     * 需审批的步骤落单挂起后**继续扫描**，不阻塞其它无依赖的步骤——
     * 退款（>100 需审批）挂起时，延迟通知仍应照常发出。
     */
    private SagaStatus run(SagaInstance saga) {
        saga.status = SagaStatus.EXECUTING;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (SagaStep step : saga.steps) {
                if (saga.done.contains(step.id()) || saga.awaitingApproval.contains(step.id())) continue;
                if (!saga.done.containsAll(step.dependsOn())) continue;
                if (requiresApproval(saga, step)) {
                    suspendForApproval(saga, step);
                    continue; // 挂起后继续扫描其它步骤，不 return
                }
                if (!executeStep(saga, step)) return fail(saga);
                saga.done.add(step.id());
                progressed = true;
            }
        }
        return settle(saga);
    }

    private boolean requiresApproval(SagaInstance saga, SagaStep step) {
        CompensationAction action = registry.get(step.actionType());
        return action != null && action.requiresApproval(saga.aggregateId, step.params());
    }

    /**
     * 收尾：全部完成则 COMPLETED；有挂起则 AWAITING_APPROVAL；有步骤永远不可调度则 FAILED。
     * <p>
     * 不可调度用拓扑排序判定：把已完成与已挂起的步骤视为已满足，对剩余步骤反复剥离依赖已满足的节点，
     * 剥不掉的即为环依赖或 dependsOn 指向不存在的 id。这样「依赖待审批步骤」不会被误判为死锁。
     */
    private SagaStatus settle(SagaInstance saga) {
        Set<String> satisfied = new HashSet<>(saga.done);
        satisfied.addAll(saga.awaitingApproval);
        List<SagaStep> remaining = saga.steps.stream()
                .filter(s -> !satisfied.contains(s.id()))
                .toList();
        Set<String> unresolved = new HashSet<>();
        for (SagaStep s : remaining) unresolved.add(s.id());

        boolean progress = true;
        while (progress) {
            progress = false;
            for (SagaStep s : remaining) {
                if (!unresolved.contains(s.id())) continue;
                if (satisfied.containsAll(s.dependsOn())) {
                    unresolved.remove(s.id());
                    satisfied.add(s.id());
                    progress = true;
                }
            }
        }
        if (!unresolved.isEmpty()) {
            log.error("[Saga] {} 存在无法满足的依赖（环或 dependsOn 指向不存在的 id），标记 FAILED：steps={}",
                    saga.sagaId, unresolved);
            return fail(saga);
        }
        if (saga.awaitingApproval.isEmpty()) {
            saga.status = SagaStatus.COMPLETED;
            instances.remove(saga.sagaId);
            if (metrics != null) {
                metrics.counter("eventguard.saga.final_status", "status", "COMPLETED");
            }
            log.info("[Saga] {} 已完成", saga.sagaId);
            return saga.status;
        }
        saga.status = SagaStatus.AWAITING_APPROVAL;
        if (metrics != null) {
            metrics.counter("eventguard.saga.final_status", "status", "AWAITING_APPROVAL");
        }
        return saga.status;
    }

    private void suspendForApproval(SagaInstance saga, SagaStep step) {
        UUID approvalId = UUID.randomUUID();
        Map<String, Object> approvalParams = new HashMap<>(step.params());
        approvalParams.put(SAGA_STEP_ID_KEY, step.id());
        approvalParams.put(SAGA_STEPS_KEY, remainingStepsJson(saga));
        approvalRepository.insert(approvalId, saga.sagaId, step.actionType(), saga.aggregateId,
                approvalParams, "saga");
        saga.awaitingApproval.add(step.id());
        log.info("[Saga] {} 步骤 {} 需审批，挂起 approvalId={}", saga.sagaId, step.id(), approvalId);
    }

    private boolean executeStep(SagaInstance saga, SagaStep step) {
        long start = System.currentTimeMillis();
        try {
            CompensationResult result = compensationService.execute(new CompensationRequest(
                    step.actionType(), saga.aggregateId, step.params()));
            log.info("[Saga] 步骤 {} 执行结果 success={} {}", step.id(), result.isSuccess(), result.getMessage());
            return result.isSuccess();
        } catch (Exception e) {
            log.error("[Saga] 步骤 {} 执行异常", step.id(), e);
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
     * 审批回调：approved 则执行该审批步骤并按依赖图继续；rejected 则标记 FAILED。
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
        SagaStep step = findAwaitingStep(saga, approval);
        if (step == null) {
            log.warn("[Saga] {} 找不到审批单对应的挂起步骤 approvalId={}", saga.sagaId, approvalId);
            return fail(saga);
        }
        if (!executeStep(saga, step)) return fail(saga);
        saga.done.add(step.id());
        saga.awaitingApproval.remove(step.id());
        return run(saga);
    }

    /** 定位审批单对应的挂起步骤：优先用 step id，老审批单回退到 actionType 匹配。 */
    private SagaStep findAwaitingStep(SagaInstance saga, ApprovalRepository.Approval approval) {
        Object stepId = approval.params().get(SAGA_STEP_ID_KEY);
        if (stepId != null) {
            return saga.steps.stream()
                    .filter(s -> s.id().equals(stepId.toString()) && saga.awaitingApproval.contains(s.id()))
                    .findFirst().orElse(null);
        }
        return saga.steps.stream()
                .filter(s -> s.actionType().equals(approval.actionType()) && saga.awaitingApproval.contains(s.id()))
                .findFirst().orElse(null);
    }

    /** 把未完成步骤（含 id 与 dependsOn）序列化为可 JSON 化的 List<Map>，存进审批单 params。 */
    private List<Map<String, Object>> remainingStepsJson(SagaInstance saga) {
        List<Map<String, Object>> json = new ArrayList<>();
        for (SagaStep s : saga.steps) {
            if (saga.done.contains(s.id())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("actionType", s.actionType());
            m.put("params", s.params());
            m.put("dependsOn", s.dependsOn());
            json.add(m);
        }
        return json;
    }

    /**
     * 启动恢复：从 PENDING 审批单重建内存实例（未完成步骤读取 params 保留键 {@link #SAGA_STEPS_KEY}）。
     * <p>
     * 只把该审批单对应的步骤标记为挂起，**不调用 run**——否则会把重启前已完成的步骤重跑一遍。
     * 审批通过时 {@link #onApproved} 才执行并继续调度。
     */
    public void recoverPending(ApprovalRepository.Approval approval) {
        Object raw = approval.params().get(SAGA_STEPS_KEY);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("[Saga] 审批单缺少剩余步骤信息，无法恢复 sagaId={}", approval.sagaId());
            return;
        }
        List<SagaStep> steps = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            if (!(m.get("actionType") instanceof String actionType)) continue;
            // 老格式审批单没有 id/dependsOn：id 回退 actionType，依赖视为空
            Object id = m.get("id");
            String stepId = id instanceof String s && !s.isBlank() ? s : actionType;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = m.get("params") instanceof Map
                    ? (Map<String, Object>) m.get("params") : Map.of();
            steps.add(new SagaStep(stepId, actionType, params, parseDependsOn(m.get("dependsOn"))));
        }
        if (steps.isEmpty()) {
            log.warn("[Saga] 审批单剩余步骤为空，无法恢复 sagaId={}", approval.sagaId());
            return;
        }
        SagaInstance saga = new SagaInstance(approval.sagaId(), approval.aggregateId(), steps);
        Object stepId = approval.params().get(SAGA_STEP_ID_KEY);
        SagaStep pending = stepId != null
                ? steps.stream().filter(s -> s.id().equals(stepId.toString())).findFirst().orElse(null)
                : steps.stream().filter(s -> s.actionType().equals(approval.actionType())).findFirst().orElse(null);
        if (pending != null) saga.awaitingApproval.add(pending.id());
        saga.status = SagaStatus.AWAITING_APPROVAL;
        instances.put(saga.sagaId, saga);
        log.info("[Saga] 启动恢复 sagaId={} aggregateId={}（未完成 {} 步，待审批 {}）",
                approval.sagaId(), approval.aggregateId(), steps.size(),
                pending == null ? "无" : pending.id());
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseDependsOn(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
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
