package com.eventguard.compensation.saga;

import com.eventguard.compensation.repository.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复：把 PENDING 审批单对应的在途 saga 重建进内存。
 *
 * 解决「server 重启后审批单还在（落库）、但 saga 实例丢失（内存），审批通过即 FAILED」的
 * 补偿中断问题。恢复后审批通过仍能继续执行剩余步骤。
 */
@Component
public class SagaRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryRunner.class);

    private final ApprovalRepository approvalRepository;
    private final CompensationSaga compensationSaga;

    public SagaRecoveryRunner(ApprovalRepository approvalRepository, CompensationSaga compensationSaga) {
        this.approvalRepository = approvalRepository;
        this.compensationSaga = compensationSaga;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ApprovalRepository.Approval> pending = approvalRepository.findPending();
            int recovered = 0;
            for (ApprovalRepository.Approval approval : pending) {
                try {
                    compensationSaga.recoverPending(approval);
                    recovered++;
                } catch (Exception e) {
                    log.warn("[Saga] 恢复 PENDING 审批单失败 approvalId={}: {}", approval.approvalId(), e.getMessage());
                }
            }
            if (recovered > 0) {
                log.info("[Saga] 启动恢复完成：重建 {} 条在途 saga（PENDING 审批单）", recovered);
            }
        } catch (Exception e) {
            // 数据库未就绪等启动期异常：不阻断应用启动，下次重启仍会尝试恢复
            log.warn("[Saga] 启动恢复跳过（数据库未就绪等）: {}", e.getMessage());
        }
    }
}
