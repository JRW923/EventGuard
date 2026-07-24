package com.eventguard.compensation.service;

import com.eventguard.command.handler.CompensationCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationCommand;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 补偿服务：校验白名单 → 转 CompensationCommand → dispatch 到 CompensationCommandHandler。
 *
 * 设计文档 7.4 MVP：人工触发版，不引入 Saga 编排。
 * ponytail: CompensationCommandHandler 现为 Spring @Component（构造注入 EventStore），此处注入复用，
 * 不再每次 new；测试改为对 commandHandler mock 断言 handle。补偿为人工触发，不接真实支付网关。
 */
@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);

    private final CompensationActionRegistry registry;
    private final CompensationCommandHandler commandHandler;

    public CompensationService(CompensationActionRegistry registry, CompensationCommandHandler commandHandler) {
        this.registry = registry;
        this.commandHandler = commandHandler;
    }

    public CompensationResult execute(CompensationRequest request) {
        String actionType = request.getActionType();
        UUID aggregateId = request.getAggregateId();

        // 0. 信任边界：aggregateId 必填，缺失直接失败（否则 null 会流入 EventStore.append）
        if (aggregateId == null) {
            log.warn("[补偿] 拒绝执行：aggregateId 为空");
            return CompensationResult.failure("aggregateId 必填");
        }

        // 1. 白名单校验
        if (!registry.isSupported(actionType)) {
            log.warn("[补偿] 拒绝执行：动作 {} 不在白名单", actionType);
            return CompensationResult.failure("动作 " + actionType + " 不在白名单");
        }

        // 2. 转补偿命令并 dispatch
        CompensationCommand cmd = new CompensationCommand(
                UUID.randomUUID(), aggregateId, actionType, request.getParams());
        try {
            CommandResult result = commandHandler.handle(cmd);
            if (result.success()) {
                // MVP：动作 execute 仅生成人工可读的执行描述，不触发真实业务副作用（见 ponytail 注释）
                var action = registry.get(actionType);
                String detail = action != null
                        ? action.execute(aggregateId, request.getParams())
                        : "补偿已执行";
                log.info("[补偿] 执行成功：{}", detail);
                return CompensationResult.success(detail + "（事件版本 " + result.version() + "）");
            } else {
                return CompensationResult.failure(result.error());
            }
        } catch (Exception e) {
            log.error("[补偿] 执行异常：{}", e.getMessage(), e);
            return CompensationResult.failure("补偿执行异常：" + e.getMessage());
        }
    }
}
