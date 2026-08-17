package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 最近告警历史接口（WS 断线补拉用），查 anomaly_alerts 表——重启后历史仍在。
 *
 * 鉴权由 AuthFilter 统一处理（有效 JWT 即可读，与 AI 的 /anomalies/{id}/analysis 一致）；
 * 路径 /alerts/recent 由 nginx 反代到本服务（/anomalies/ 前缀被反代到 AI，不能复用）。
 */
@RestController
@RequestMapping("/alerts")
public class AlertHistoryController {

    private final AnomalyAlertHistoryRepository repository;
    private final int defaultLimit;

    public AlertHistoryController(AnomalyAlertHistoryRepository repository,
                                  @Value("${eg.alerts.recent-capacity:100}") int defaultLimit) {
        this.repository = repository;
        this.defaultLimit = Math.max(1, defaultLimit);
    }

    @GetMapping("/recent")
    public List<AnomalyAlert> recent(@RequestParam(required = false) Integer limit) {
        return repository.recent(limit != null ? Math.min(Math.max(limit, 1), 500) : defaultLimit);
    }
}
