package com.eventguard.gateway.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 网关请求记录（outbox + 回调关联表）：记录一次网关出站请求的状态，
 * 支付异步回调按 external_ref（网关侧支付单号）反查关联的 command/aggregate。
 */
public class GatewayRequest {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    private final UUID id;
    private final UUID commandId;
    private final UUID aggregateId;
    private final String gatewayType;   // PAYMENT / INVENTORY / NOTIFICATION
    private final String requestType;   // CREATE_PAYMENT / CAPTURE / REFUND / RESERVE / NOTIFY
    private final String provider;      // mock / alipay / ...
    private final String externalRef;   // 网关侧单号（回调反查键）
    private final Status status;
    private final Map<String, Object> requestPayload;
    private final Map<String, Object> responsePayload;
    private final Instant createdAt;
    private final Instant updatedAt;

    public GatewayRequest(UUID id, UUID commandId, UUID aggregateId, String gatewayType, String requestType,
                          String provider, String externalRef, Status status,
                          Map<String, Object> requestPayload, Map<String, Object> responsePayload,
                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.commandId = commandId;
        this.aggregateId = aggregateId;
        this.gatewayType = gatewayType;
        this.requestType = requestType;
        this.provider = provider;
        this.externalRef = externalRef;
        this.status = status;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getCommandId() { return commandId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getGatewayType() { return gatewayType; }
    public String getRequestType() { return requestType; }
    public String getProvider() { return provider; }
    public String getExternalRef() { return externalRef; }
    public Status getStatus() { return status; }
    public Map<String, Object> getRequestPayload() { return requestPayload; }
    public Map<String, Object> getResponsePayload() { return responsePayload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isTerminal() { return status == Status.SUCCEEDED || status == Status.FAILED; }
}
