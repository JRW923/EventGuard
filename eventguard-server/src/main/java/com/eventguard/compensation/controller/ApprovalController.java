package com.eventguard.compensation.controller;

import com.eventguard.auth.security.RequirePermission;
import com.eventguard.compensation.repository.ApprovalRepository;
import com.eventguard.compensation.saga.CompensationSaga;
import com.eventguard.compensation.saga.SagaStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批流端点（对齐设计文档 7.4.4）：人工审批/拒绝高风险补偿步骤。
 * 人工补偿入口 POST /compensations 保持，Saga 挂起的审批单在此决策。
 */
@RestController
@RequestMapping("/approvals")
@RequirePermission("compensation:execute")
public class ApprovalController {

    private final ApprovalRepository approvalRepository;
    private final CompensationSaga compensationSaga;

    public ApprovalController(ApprovalRepository approvalRepository, CompensationSaga compensationSaga) {
        this.approvalRepository = approvalRepository;
        this.compensationSaga = compensationSaga;
    }

    public record ApprovalView(UUID approvalId, UUID sagaId, String actionType, UUID aggregateId,
                               Object params, String status, String requestedBy, String requestedAt) {
        static ApprovalView from(ApprovalRepository.Approval a) {
            return new ApprovalView(a.approvalId(), a.sagaId(), a.actionType(), a.aggregateId(),
                    cleanParams(a.params()), a.status(), a.requestedBy(),
                    a.requestedAt() != null ? a.requestedAt().toString() : null);
        }

        /** 过滤保留键（__ 前缀，如 saga 剩余步骤）：只把业务参数暴露给前端。 */
        private static Object cleanParams(Object params) {
            if (!(params instanceof Map<?, ?> m)) {
                return params;
            }
            Map<String, Object> filtered = new java.util.HashMap<>();
            m.forEach((k, v) -> {
                if (!(k instanceof String s) || !s.startsWith("__")) {
                    filtered.put(String.valueOf(k), v);
                }
            });
            return filtered;
        }
    }

    public record DecisionRequest(String decidedBy) {}

    @GetMapping
    public ResponseEntity<List<ApprovalView>> listPending() {
        return ResponseEntity.ok(approvalRepository.findPending().stream().map(ApprovalView::from).toList());
    }

    @PostMapping("/{approvalId}/approve")
    public ResponseEntity<SagaStatus> approve(@PathVariable UUID approvalId,
                                              @RequestBody(required = false) DecisionRequest req) {
        Optional<ApprovalRepository.Approval> a = approvalRepository.findByApprovalId(approvalId);
        if (a.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(compensationSaga.onApproved(approvalId, true,
                req != null && req.decidedBy() != null ? req.decidedBy() : "operator"));
    }

    @PostMapping("/{approvalId}/reject")
    public ResponseEntity<SagaStatus> reject(@PathVariable UUID approvalId,
                                             @RequestBody(required = false) DecisionRequest req) {
        Optional<ApprovalRepository.Approval> a = approvalRepository.findByApprovalId(approvalId);
        if (a.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(compensationSaga.onApproved(approvalId, false,
                req != null && req.decidedBy() != null ? req.decidedBy() : "operator"));
    }
}
