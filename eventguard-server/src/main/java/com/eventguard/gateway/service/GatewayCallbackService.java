package com.eventguard.gateway.service;

import com.eventguard.command.command.CompletePaymentCommand;
import com.eventguard.command.command.FailPaymentCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.model.GatewayRequest;
import com.eventguard.gateway.repository.GatewayRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
        Optional<GatewayRequest> reqOpt = gatewayRequestRepository.findByExternalRef(externalRef);
        if (reqOpt.isPresent()) {
            GatewayRequest req = reqOpt.get();
            if (req.isTerminal()) {
                log.info("[支付回调] 已终态（幂等跳过）externalRef={} status={}", externalRef, req.getStatus());
                return CommandResult.success(0);
            }
            java.util.Map<String, Object> resultPayload = new java.util.HashMap<>();
            resultPayload.put("result", success ? "SUCCEEDED" : "FAILED");
            resultPayload.put("error", error);
            gatewayRequestRepository.updateStatus(externalRef,
                    success ? GatewayRequest.Status.SUCCEEDED : GatewayRequest.Status.FAILED,
                    resultPayload);
        } else {
            log.warn("[支付回调] 未找到对应 gateway_request（externalRef={}），按结果直接派发", externalRef);
        }

        if (success) {
            log.info("[支付回调] 支付成功 externalRef={} order={}", externalRef, orderId);
            return orderCommandHandler.handle(new CompletePaymentCommand(
                    UUID.randomUUID(), orderId, externalRef));
        }
        log.warn("[支付回调] 支付失败 externalRef={} order={} reason={}", externalRef, orderId, error);
        return orderCommandHandler.handle(new FailPaymentCommand(
                UUID.randomUUID(), orderId, error != null ? error : "网关支付失败"));
    }
}
