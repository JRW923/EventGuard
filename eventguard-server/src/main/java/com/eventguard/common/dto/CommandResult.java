package com.eventguard.common.dto;

import java.util.UUID;

public record CommandResult(boolean success, int version, String error) {
    public static CommandResult success(int version) { return new CommandResult(true, version, null); }
    public static CommandResult failure(String error) { return new CommandResult(false, -1, error); }
}
