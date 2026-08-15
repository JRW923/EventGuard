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
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Map;

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

        var action = registry.get(actionType);
        Map<String, Object> actionParams = request.getParams() == null
                ? new HashMap<>() : new HashMap<>(request.getParams());
        String idempotencyKey = aggregateId + ":" + actionType + ":" + new TreeMap<>(actionParams);
        actionParams.putIfAbsent("__idempotency_key", idempotencyKey);
        CompensationResult actionResult = action.executeResult(aggregateId, actionParams);
        if (!actionResult.isSuccess()) {
            log.warn("[补偿] 动作执行失败，未写入完成事件：{}", actionResult.getMessage());
            return actionResult;
        }

        // 2. 外部动作成功后再记录完成事件，避免事件语义与真实结果相反。
        CompensationCommand cmd = new CompensationCommand(
                UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)), aggregateId, actionType, actionParams);
        try {
            CommandResult result = commandHandler.handle(cmd);
            if (result.success()) {
                log.info("[补偿] 执行成功：{}", actionResult.getMessage());
                return CompensationResult.success(actionResult.getMessage() + "（事件版本 " + result.version() + "）");
            } else {
                return CompensationResult.failure(result.error());
            }
        } catch (Exception e) {
            log.error("[补偿] 执行异常：{}", e.getMessage(), e);
            return CompensationResult.failure("补偿执行异常：" + e.getMessage());
        }
    }
}
