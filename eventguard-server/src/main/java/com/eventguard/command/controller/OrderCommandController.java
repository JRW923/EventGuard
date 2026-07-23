package com.eventguard.command.controller;

import com.eventguard.command.command.*;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderCommandController {

    private final OrderCommandHandler handler;

    public OrderCommandController(OrderCommandHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    public ResponseEntity<CommandResult> createOrder(@RequestBody CreateOrderRequest req) {
        CreateOrderCommand cmd = new CreateOrderCommand(
                UUID.randomUUID(),
                req.orderId() != null ? req.orderId() : UUID.randomUUID(),
                req.userId(),
                req.totalAmount()
        );
        return ResponseEntity.ok(handler.handle(cmd));
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<CommandResult> pay(@PathVariable UUID orderId, @RequestBody PayRequest req) {
        return ResponseEntity.ok(handler.handle(
                new PayOrderCommand(UUID.randomUUID(), orderId, req.paymentId())));
    }

    @PostMapping("/{orderId}/fail-payment")
    public ResponseEntity<CommandResult> failPayment(@PathVariable UUID orderId, @RequestBody FailPaymentRequest req) {
        return ResponseEntity.ok(handler.handle(
                new FailPaymentCommand(UUID.randomUUID(), orderId, req.reason())));
    }

    @PostMapping("/{orderId}/retry-payment")
    public ResponseEntity<CommandResult> retryPayment(@PathVariable UUID orderId) {
        return ResponseEntity.ok(handler.handle(
                new RetryPaymentCommand(UUID.randomUUID(), orderId)));
    }

    @PostMapping("/{orderId}/reserve-inventory")
    public ResponseEntity<CommandResult> reserveInventory(@PathVariable UUID orderId, @RequestBody ReserveInventoryRequest req) {
        return ResponseEntity.ok(handler.handle(
                new ReserveInventoryCommand(UUID.randomUUID(), orderId, req.skuId(), req.quantity())));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<CommandResult> confirm(@PathVariable UUID orderId) {
        return ResponseEntity.ok(handler.handle(
                new ConfirmOrderCommand(UUID.randomUUID(), orderId)));
    }

    @PostMapping("/{orderId}/ship")
    public ResponseEntity<CommandResult> ship(@PathVariable UUID orderId, @RequestBody ShipRequest req) {
        return ResponseEntity.ok(handler.handle(
                new ShipOrderCommand(UUID.randomUUID(), orderId, req.trackingNo())));
    }

    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<CommandResult> deliver(@PathVariable UUID orderId) {
        return ResponseEntity.ok(handler.handle(
                new DeliverOrderCommand(UUID.randomUUID(), orderId)));
    }

    @PostMapping("/{orderId}/close")
    public ResponseEntity<CommandResult> close(@PathVariable UUID orderId) {
        return ResponseEntity.ok(handler.handle(
                new CloseOrderCommand(UUID.randomUUID(), orderId)));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CommandResult> cancel(@PathVariable UUID orderId, @RequestBody CancelRequest req) {
        return ResponseEntity.ok(handler.handle(
                new CancelOrderCommand(UUID.randomUUID(), orderId, req.reason())));
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<CommandResult> refund(@PathVariable UUID orderId, @RequestBody RefundRequest req) {
        return ResponseEntity.ok(handler.handle(
                new RefundOrderCommand(UUID.randomUUID(), orderId, req.refundAmount())));
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
