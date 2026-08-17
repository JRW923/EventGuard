package com.eventguard.query.controller;

import com.eventguard.auth.security.RequirePermission;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.service.OrderQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequirePermission("order:read")
public class OrderQueryController {

    private final OrderQueryService queryService;

    public OrderQueryController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{orderId}")
    public Object getOrder(@PathVariable UUID orderId,
                           @RequestParam(required = false) Integer expectedVersion) {
        if (expectedVersion != null) {
            // DeferredResult：等待投影追平期间释放 Web 线程（通知/兜底轮询在后台线程完成）
            DeferredResult<ResponseEntity<OrderView>> deferred = new DeferredResult<>();
            queryService.readAfterWriteAsync(orderId, expectedVersion).whenComplete((ov, ex) -> {
                if (ex != null) {
                    deferred.setErrorResult(
                            new ResponseStatusException(HttpStatus.CONFLICT, "读模型同步中，请稍后重试"));
                } else {
                    deferred.setResult(ResponseEntity.ok(ov));
                }
            });
            return deferred;
        }
        return queryService.findById(orderId)
                .<ResponseEntity<OrderView>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public com.eventguard.query.model.OrderListResponse listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page>=0 且 size 范围为 1..100");
        }
        return queryService.listOrders(status, page, size);
    }

    @GetMapping("/{orderId}/events")
    public List<com.eventguard.query.model.EventDto> getEvents(@PathVariable java.util.UUID orderId,
                                                               @RequestParam(required = false) Integer upToVersion) {
        return queryService.getEvents(orderId, upToVersion);
    }
}
