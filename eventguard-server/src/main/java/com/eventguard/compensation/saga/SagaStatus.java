package com.eventguard.compensation.saga;

/** Saga 状态（对齐设计文档 7.4.1）。 */
public enum SagaStatus {
    STARTED, AWAITING_APPROVAL, EXECUTING, COMPLETED, FAILED
}
