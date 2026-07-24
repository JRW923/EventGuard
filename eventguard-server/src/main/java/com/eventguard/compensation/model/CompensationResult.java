package com.eventguard.compensation.model;

/**
 * 补偿执行结果。
 */
public class CompensationResult {

    private boolean success;
    private String message;

    public CompensationResult() {}

    public CompensationResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static CompensationResult success(String message) {
        return new CompensationResult(true, message);
    }

    public static CompensationResult failure(String message) {
        return new CompensationResult(false, message);
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
