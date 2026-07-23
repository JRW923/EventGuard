package com.eventguard.command.aggregate;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_FAILED,
    PAID,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CLOSED,
    CANCELLED,
    REFUNDED
}
