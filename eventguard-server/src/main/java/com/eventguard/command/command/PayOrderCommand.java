package com.eventguard.command.command;

import java.util.UUID;

public record PayOrderCommand(UUID commandId, UUID orderId, String paymentId) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
