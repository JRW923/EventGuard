package com.eventguard.command.command;

import java.util.UUID;

public interface Command {
    UUID getCommandId();
    UUID getAggregateId();
}
