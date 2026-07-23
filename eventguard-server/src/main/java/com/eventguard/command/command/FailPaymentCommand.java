package com.eventguard.command.command;

import java.util.UUID;

public record FailPaymentCommand(UUID commandId, UUID orderId, String reason) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
