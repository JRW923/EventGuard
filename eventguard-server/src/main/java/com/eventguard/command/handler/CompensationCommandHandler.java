package com.eventguard.command.handler;

import com.eventguard.common.dto.CommandResult;
import com.eventguard.compensation.model.CompensationCommand;
import com.eventguard.event.model.CompensationExecutedEvent;
import com.eventguard.event.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 补偿命令处理器：接收 CompensationCommand，生成 CompensationExecutedEvent 写入事件存储。
 *
 * MVP 简化版：补偿事件作为订单事件流的一部分记录（版本号续接）。
 * ponytail: 聚合当前版本通过 EventStore.load 取最大版本号近似（O(n) 扫描），生产应走 AggregateRepository.load；
 * 补偿为人工触发、低频，简化可接受。
 */
@Component
public class CompensationCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CompensationCommandHandler.class);
    private final EventStore eventStore;

    public CompensationCommandHandler(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    public CommandResult handle(CompensationCommand cmd) {
        log.info("[补偿] 执行 {} 于聚合 {}", cmd.actionType(), cmd.aggregateId());

        int currentVersion = loadCurrentVersion(cmd.aggregateId());
        int newVersion = currentVersion + 1;

        CompensationExecutedEvent event = new CompensationExecutedEvent(
                cmd.aggregateId(), newVersion, cmd.actionType(), cmd.params(), null);

        eventStore.append(cmd.aggregateId(), List.of(event), currentVersion);
        return CommandResult.success(newVersion);
    }

    private int loadCurrentVersion(UUID aggregateId) {
        try {
            var events = eventStore.load(aggregateId);
            return events.stream().mapToInt(e -> e.getVersion()).max().orElse(0);
        } catch (Exception e) {
            log.warn("加载聚合 {} 当前版本失败，使用 0：{}", aggregateId, e.getMessage());
            return 0;
        }
    }
}
