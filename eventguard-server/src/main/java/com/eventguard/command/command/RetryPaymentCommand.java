package com.eventguard.command.command;

import java.util.UUID;

public record RetryPaymentCommand(UUID commandId, UUID orderId) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
