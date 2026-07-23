package com.eventguard.command.command;

import java.math.BigDecimal;
import java.util.UUID;

public record RefundOrderCommand(UUID commandId, UUID orderId, BigDecimal refundAmount) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
