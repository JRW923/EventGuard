package com.eventguard.gateway.controller;

import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.service.GatewayCallbackService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 网关回调端点：真实网关（支付宝等）回调入口。
 * <p>
 * 鉴权：AuthFilter 对 /gateway/** 放行（机器回调不带用户 JWT），本端点内自行校验
 * {@code X-API-Key == EG_MACHINE_API_KEY}，不合法直接 401。回调结果经 GatewayCallbackService
 * 派发 CompletePaymentCommand / FailPaymentCommand 走事件溯源链路。
 */
@RestController
@RequestMapping("/gateway/callback")
public class GatewayCallbackController {

    private final GatewayCallbackService callbackService;
    private final String machineApiKey;

    public GatewayCallbackController(GatewayCallbackService callbackService,
                                     @Value("${EG_MACHINE_API_KEY:dev-machine-key}") String machineApiKey) {
        this.callbackService = callbackService;
        this.machineApiKey = machineApiKey;
    }

    public record CallbackRequest(String externalRef, UUID orderId, boolean success, String error) {}

    @PostMapping("/{provider}")
    public ResponseEntity<CommandResult> callback(@PathVariable String provider,
                                                  @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                                  @RequestBody CallbackRequest req) {
        if (!machineApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(callbackService.process(req.externalRef(), req.orderId(), req.success(), req.error()));
    }
}
