package com.eventguard.command.command;

import java.util.UUID;

public record ShipOrderCommand(UUID commandId, UUID orderId, String trackingNo) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
