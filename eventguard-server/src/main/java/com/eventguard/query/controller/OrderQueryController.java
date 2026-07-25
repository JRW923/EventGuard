package com.eventguard.query.controller;

import com.eventguard.common.exception.ProjectionLagException;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.service.OrderQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderQueryController {

    private final OrderQueryService queryService;

    public OrderQueryController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> getOrder(@PathVariable UUID orderId,
                                              @RequestParam(required = false) Integer expectedVersion) {
        if (expectedVersion != null) {
            try {
                return ResponseEntity.ok(queryService.readAfterWrite(orderId, expectedVersion));
            } catch (ProjectionLagException e) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }
        return queryService.findById(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public com.eventguard.query.model.OrderListResponse listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.listOrders(status, page, size);
    }

    @GetMapping("/{orderId}/events")
    public List<com.eventguard.query.model.EventDto> getEvents(@PathVariable java.util.UUID orderId,
                                                               @RequestParam(required = false) Integer upToVersion) {
        return queryService.getEvents(orderId, upToVersion);
    }
}
