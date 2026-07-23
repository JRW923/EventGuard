package com.eventguard.command.command;

import java.util.UUID;

public record ConfirmOrderCommand(UUID commandId, UUID orderId) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
