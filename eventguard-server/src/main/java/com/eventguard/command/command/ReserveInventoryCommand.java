package com.eventguard.command.command;

import java.util.UUID;

public record ReserveInventoryCommand(UUID commandId, UUID orderId, String skuId, int quantity) implements Command {
    @Override public UUID getCommandId() { return commandId; }
    @Override public UUID getAggregateId() { return orderId; }
}
