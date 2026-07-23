package com.eventguard.command.command;

import java.util.UUID;

public record DeliverOrderCommand(UUID commandId, UUID orderId) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
