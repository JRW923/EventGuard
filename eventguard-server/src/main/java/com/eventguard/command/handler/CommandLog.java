package com.eventguard.command.handler;

import java.time.Instant;
import java.util.UUID;

/**
 * 命令日志实体：用于幂等命令处理。
 * 同一 commandId 重复提交时，直接返回首次执行结果。
 */
public class CommandLog {
    private final UUID commandId;
    private final UUID aggregateId;
    private final String commandType;
    private final String resultJson;
    private final Instant executedAt;

    public CommandLog(UUID commandId, UUID aggregateId, String commandType, String resultJson, Instant executedAt) {
        this.commandId = commandId;
        this.aggregateId = aggregateId;
        this.commandType = commandType;
        this.resultJson = resultJson;
        this.executedAt = executedAt;
    }

    public UUID getCommandId() { return commandId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getCommandType() { return commandType; }
    public String getResultJson() { return resultJson; }
    public Instant getExecutedAt() { return executedAt; }
}
