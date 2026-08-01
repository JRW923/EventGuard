package com.eventguard.command.controller;

import com.eventguard.auth.security.RequirePermission;
import com.eventguard.command.command.*;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 订单写命令。类级默认 order:write；新建订单单独要求 order:create。
 */
@RestController
@RequestMapping("/orders")
@RequirePermission("order:write")
public class OrderCommandController {

    private final OrderCommandHandler handler;

    public OrderCommandController(OrderCommandHandler handler) {
        this.handler = handler;
    }

    /**
     * 从 X-Command-Id 头解析客户端幂等键；缺失或非法时回退服务端生成。
     * 同一客户端键可让超时重试命中命令日志缓存，避免重复处理。
     */
    private UUID commandId(String header) {
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 回退到服务端生成
            }
        }
        return UUID.randomUUID();
    }

    @PostMapping
    @RequirePermission("order:create")
    public ResponseEntity<CommandResult> createOrder(
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody CreateOrderRequest req) {
        UUID orderId = req.orderId() != null ? req.orderId() : UUID.randomUUID();
        CreateOrderCommand cmd = new CreateOrderCommand(
                commandId(commandIdHeader),
                orderId,
                req.userId(),
                req.totalAmount()
        );
        CommandResult result = handler.handle(cmd);
        return ResponseEntity.ok(new CommandResult(result.success(), result.version(), result.error(), orderId));
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<CommandResult> pay(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody PayRequest req) {
        return ResponseEntity.ok(handler.handle(
                new PayOrderCommand(commandId(commandIdHeader), orderId, req.paymentId())));
    }

    @PostMapping("/{orderId}/fail-payment")
    public ResponseEntity<CommandResult> failPayment(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody FailPaymentRequest req) {
        return ResponseEntity.ok(handler.handle(
                new FailPaymentCommand(commandId(commandIdHeader), orderId, req.reason())));
    }

    @PostMapping("/{orderId}/retry-payment")
    public ResponseEntity<CommandResult> retryPayment(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader) {
        return ResponseEntity.ok(handler.handle(
                new RetryPaymentCommand(commandId(commandIdHeader), orderId)));
    }

    @PostMapping("/{orderId}/reserve-inventory")
    public ResponseEntity<CommandResult> reserveInventory(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody ReserveInventoryRequest req) {
        return ResponseEntity.ok(handler.handle(
                new ReserveInventoryCommand(commandId(commandIdHeader), orderId, req.skuId(), req.quantity())));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<CommandResult> confirm(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader) {
        return ResponseEntity.ok(handler.handle(
                new ConfirmOrderCommand(commandId(commandIdHeader), orderId)));
    }

    @PostMapping("/{orderId}/ship")
    public ResponseEntity<CommandResult> ship(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody ShipRequest req) {
        return ResponseEntity.ok(handler.handle(
                new ShipOrderCommand(commandId(commandIdHeader), orderId, req.trackingNo())));
    }

    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<CommandResult> deliver(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader) {
        return ResponseEntity.ok(handler.handle(
                new DeliverOrderCommand(commandId(commandIdHeader), orderId)));
    }

    @PostMapping("/{orderId}/close")
    public ResponseEntity<CommandResult> close(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader) {
        return ResponseEntity.ok(handler.handle(
                new CloseOrderCommand(commandId(commandIdHeader), orderId)));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CommandResult> cancel(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody CancelRequest req) {
        return ResponseEntity.ok(handler.handle(
                new CancelOrderCommand(commandId(commandIdHeader), orderId, req.reason())));
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<CommandResult> refund(@PathVariable UUID orderId,
            @RequestHeader(value = "X-Command-Id", required = false) String commandIdHeader,
            @RequestBody RefundRequest req) {
        return ResponseEntity.ok(handler.handle(
                new RefundOrderCommand(commandId(commandIdHeader), orderId, req.refundAmount())));
    }

    // —— 请求 DTO ——
    public record CreateOrderRequest(UUID orderId, String userId, BigDecimal totalAmount) {}
    public record PayRequest(String paymentId) {}
    public record FailPaymentRequest(String reason) {}
    public record ReserveInventoryRequest(String skuId, int quantity) {}
    public record ShipRequest(String trackingNo) {}
    public record CancelRequest(String reason) {}
    public record RefundRequest(BigDecimal refundAmount) {}
}
