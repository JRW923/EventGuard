package com.eventguard.gateway.controller;

import com.eventguard.auth.config.ProductionSecurityGuard;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
    private final String callbackSecret;
    private final boolean signatureRequired;

    public GatewayCallbackController(GatewayCallbackService callbackService,
                                     @Value("${EG_MACHINE_API_KEY:" + ProductionSecurityGuard.DEFAULT_MACHINE_KEY + "}")
                                     String machineApiKey,
                                     @Value("${EG_GATEWAY_CALLBACK_SECRET:}") String callbackSecret,
                                     @Value("${EG_GATEWAY_CALLBACK_SIGNATURE_REQUIRED:false}") boolean signatureRequired) {
        this.callbackService = callbackService;
        this.machineApiKey = machineApiKey;
        this.callbackSecret = callbackSecret;
        this.signatureRequired = signatureRequired;
    }

    public record CallbackRequest(String externalRef, UUID orderId, boolean success, String error) {}

    @PostMapping("/{provider}")
    public ResponseEntity<CommandResult> callback(@PathVariable String provider,
                                                  @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                                  @RequestHeader(value = "X-Callback-Id", required = false) String callbackId,
                                                  @RequestHeader(value = "X-Callback-Timestamp", required = false) String timestamp,
                                                  @RequestHeader(value = "X-Callback-Signature", required = false) String signature,
                                                  @RequestBody CallbackRequest req) {
        if (!machineApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }
        if (req.externalRef() == null || req.externalRef().isBlank() || req.orderId() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (signatureRequired && !validSignature(provider, callbackId, timestamp, signature, req)) {
            return ResponseEntity.status(401).build();
        }
        try {
            return ResponseEntity.ok(callbackService.process(provider, req.externalRef(), req.orderId(),
                    req.success(), req.error(), callbackId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    private boolean validSignature(String provider, String callbackId, String timestamp,
                                   String signature, CallbackRequest req) {
        if (callbackSecret == null || callbackSecret.isBlank() || timestamp == null || signature == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
                return false;
            }
            String payload = String.join("|", provider, value(callbackId), timestamp,
                    req.externalRef(), req.orderId().toString(), Boolean.toString(req.success()), value(req.error()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
