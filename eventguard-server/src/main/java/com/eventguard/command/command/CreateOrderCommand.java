package com.eventguard.command.command;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderCommand(UUID commandId, UUID orderId, String userId,
                                 BigDecimal totalAmount) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
