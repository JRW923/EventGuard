package com.eventguard.command.controller;

import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        CommandResult result = handler.handle(cmd);
        return ResponseEntity.ok(result);
    }

    public record CreateOrderRequest(UUID orderId, String userId, BigDecimal totalAmount) {}
}
