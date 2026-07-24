package com.eventguard.compensation.model;

import com.eventguard.command.command.Command;

import java.util.Map;
import java.util.UUID;

/**
 * 补偿命令（实现 Command 接口，由 CompensationCommandHandler 处理）。
 */
public record CompensationCommand(
        UUID commandId,
        UUID aggregateId,
        String actionType,
        Map<String, Object> params
) implements Command {
    @Override
    public UUID getCommandId() { return commandId; }
    @Override
    public UUID getAggregateId() { return aggregateId; }
}
