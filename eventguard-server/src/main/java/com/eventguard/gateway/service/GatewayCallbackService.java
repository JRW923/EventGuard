package com.eventguard.gateway.service;

import com.eventguard.command.command.CompletePaymentCommand;
import com.eventguard.command.command.FailPaymentCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.gateway.model.GatewayRequest;
import com.eventguard.gateway.repository.GatewayRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * 网关回调处理：支付异步回调按 external_ref 反查 gateway_request，
 * 终态则幂等返回，否则更新状态并按结果派发 CompletePaymentCommand / FailPaymentCommand。
 * <p>
 * 幂等双保险：回调反查若已是 SUCCEEDED/FAILED 直接返回缓存结果；
 * 命令侧 CompletePaymentCommand.commandId 走 command_log 幂等，回调重放不会重复发事件。
 */
@Service
public class GatewayCallbackService {

    private static final Logger log = LoggerFactory.getLogger(GatewayCallbackService.class);

    private final GatewayRequestRepository gatewayRequestRepository;
    private final OrderCommandHandler orderCommandHandler;

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public GatewayCallbackService(GatewayRequestRepository gatewayRequestRepository,
                                  OrderCommandHandler orderCommandHandler) {
        this.gatewayRequestRepository = gatewayRequestRepository;
        this.orderCommandHandler = orderCommandHandler;
    }

    /**
     * 处理支付回调结果。
     *
     * @param externalRef 网关侧支付单号（与 gateway_request.external_ref 对应）
     * @param success     是否支付成功
     * @param error       失败原因（success=true 时可空）
     */
    public CommandResult process(String externalRef, UUID orderId, boolean success, String error) {
        long start = System.currentTimeMillis();
        try {
            return doProcess(null, externalRef, orderId, success, error, null);
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.payment.callback.duration", System.currentTimeMillis() - start,
                        "success", String.valueOf(success));
            }
        }
    }

    public CommandResult process(String provider, String externalRef, UUID orderId,
                                 boolean success, String error, String callbackId) {
        long start = System.currentTimeMillis();
        try {
            return doProcess(provider, externalRef, orderId, success, error, callbackId);
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.payment.callback.duration", System.currentTimeMillis() - start,
                        "success", String.valueOf(success));
            }
        }
    }

    /** 发起流程已完成 gateway_request 落库时复用同一记录，避免回调竞态下依赖二次查询。 */
    public CommandResult process(GatewayRequest request, boolean success, String error, String callbackId) {
        long start = System.currentTimeMillis();
        try {
            return doProcess(request.getProvider(), request, request.getAggregateId(), success, error, callbackId);
        } finally {
            if (metrics != null) {
                metrics.record("eventguard.payment.callback.duration", System.currentTimeMillis() - start,
                        "success", String.valueOf(success));
            }
        }
    }

    private CommandResult doProcess(String provider, String externalRef, UUID orderId,
                                    boolean success, String error, String callbackId) {
        Optional<GatewayRequest> reqOpt = gatewayRequestRepository.findByExternalRef(externalRef);
        if (reqOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到对应 gateway_request: " + externalRef);
        }
        return doProcess(provider, reqOpt.get(), orderId, success, error, callbackId);
    }

    private CommandResult doProcess(String provider, GatewayRequest req, UUID orderId,
                                    boolean success, String error, String callbackId) {
        String externalRef = req.getExternalRef();
        if (provider != null && req.getProvider() != null && !provider.equalsIgnoreCase(req.getProvider())) {
            throw new IllegalArgumentException("回调 provider 与原请求不一致");
        }
        if (req.getAggregateId() == null || !req.getAggregateId().equals(orderId)) {
            throw new IllegalArgumentException("回调订单与 gateway_request 不一致");
        }
        if (req.isTerminal()) {
            log.info("[支付回调] 已终态（幂等跳过）externalRef={} status={}", externalRef, req.getStatus());
            return CommandResult.success(0);
        }
        java.util.Map<String, Object> resultPayload = new java.util.HashMap<>();
        resultPayload.put("result", success ? "SUCCEEDED" : "FAILED");
        resultPayload.put("error", error);
        resultPayload.put("callback_id", callbackId);
        gatewayRequestRepository.updateStatus(externalRef,
                success ? GatewayRequest.Status.SUCCEEDED : GatewayRequest.Status.FAILED,
                resultPayload);

        UUID commandId = stableCommandId(callbackId, externalRef, success);

        if (success) {
            log.info("[支付回调] 支付成功 externalRef={} order={}", externalRef, orderId);
            return orderCommandHandler.handle(new CompletePaymentCommand(
                    commandId, orderId, externalRef));
        }
        log.warn("[支付回调] 支付失败 externalRef={} order={} reason={}", externalRef, orderId, error);
        return orderCommandHandler.handle(new FailPaymentCommand(
                commandId, orderId, error != null ? error : "网关支付失败"));
    }

    private UUID stableCommandId(String callbackId, String externalRef, boolean success) {
        String seed = (callbackId == null || callbackId.isBlank() ? externalRef : callbackId)
                + "|" + success;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
