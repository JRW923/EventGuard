package com.eventguard.query.controller;

import com.eventguard.query.model.OrderStats;
import com.eventguard.query.service.OrderStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 订单统计 REST 接口（GET /orders/stats）。
 */
@RestController
@RequestMapping("/orders/stats")
public class OrderStatsController {

    private final OrderStatsService statsService;

    public OrderStatsController(OrderStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    public List<OrderStats> getStats(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return statsService.getStats(status, from, to);
    }
}
