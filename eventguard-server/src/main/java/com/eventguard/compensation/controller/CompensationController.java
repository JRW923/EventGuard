package com.eventguard.compensation.controller;

import com.eventguard.auth.security.RequirePermission;
import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import com.eventguard.compensation.model.SagaRequest;
import com.eventguard.compensation.saga.CompensationSaga;
import com.eventguard.compensation.saga.SagaStatus;
import com.eventguard.compensation.saga.SagaStep;
import com.eventguard.compensation.service.CompensationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 补偿执行 REST 接口（POST /compensations）。
 * <p>
 * 两条路径：
 * - POST /compensations       人工立即执行（保持原语义，绕过审批）
 * - POST /compensations/saga  AI 建议的补偿步骤走 Saga（Item 6b）：高风险步骤自动落审批单等人工决策，
 *                              低风险步骤直接执行。复用现有 CompensationSaga 编排，不新增状态机。
 */
@RestController
@RequestMapping("/compensations")
@RequirePermission("compensation:execute")
public class CompensationController {

    private final CompensationService service;
    private final CompensationSaga saga;
    private final CompensationActionRegistry registry;

    public CompensationController(CompensationService service,
                                  CompensationSaga saga,
                                  CompensationActionRegistry registry) {
        this.service = service;
        this.saga = saga;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<CompensationResult> execute(@RequestBody CompensationRequest request) {
        CompensationResult result = service.execute(request);
        // ponytail: 计划 verify 清单要求 unknown action 返回 400；白名单拒绝属失败，统一按成功/失败映射 200/400
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/saga")
    public ResponseEntity<?> startSaga(@RequestBody SagaRequest request) {
        if (request.getAggregateId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "aggregateId 不能为空"));
        }
        if (request.getSteps() == null || request.getSteps().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "steps 不能为空"));
        }
        List<SagaStep> steps = request.getSteps().stream()
                .map(s -> new SagaStep(s.getActionType(),
                        s.getParams() != null ? s.getParams() : Map.of()))
                .toList();
        // 白名单校验：拒绝 AI 建议的白名单外动作（安全边界，参考设计文档 7.4）
        for (SagaStep step : steps) {
            if (!registry.isSupported(step.actionType())) {
                return ResponseEntity.badRequest().body(Map.of("message", "动作不在白名单: " + step.actionType()));
            }
        }
        SagaStatus status = saga.start(request.getAggregateId(), steps);
        return ResponseEntity.ok(Map.of("status", status.name()));
    }
}
