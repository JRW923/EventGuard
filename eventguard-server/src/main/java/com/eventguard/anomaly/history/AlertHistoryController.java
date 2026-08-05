package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 最近告警历史接口（WS 断线补拉用）。
 *
 * 鉴权由 AuthFilter 统一处理（有效 JWT 即可读，与 AI 的 /anomalies/{id}/analysis 一致）；
 * 路径 /alerts/recent 由 nginx 反代到本服务（/anomalies/ 前缀被反代到 AI，不能复用）。
 */
@RestController
@RequestMapping("/alerts")
public class AlertHistoryController {

    private final RecentAlertsBuffer buffer;

    public AlertHistoryController(RecentAlertsBuffer buffer) {
        this.buffer = buffer;
    }

    @GetMapping("/recent")
    public List<AnomalyAlert> recent() {
        return buffer.recent();
    }
}
