package com.eventguard.anomaly.controller;

import com.eventguard.anomaly.engine.RuleEngine;
import com.eventguard.anomaly.model.Anomaly;
import com.eventguard.auth.security.RequirePermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 规则引擎 REST 接口：供 AI 服务（M3.5）通过 HTTP 调用。
 * 不作为独立 Kafka 消费者，避免与 AI 侧重复告警。
 * 仅机器主体（EG_MACHINE_API_KEY）可访问。
 */
@RestController
@RequestMapping("/anomaly/rules")
@RequirePermission("anomaly:evaluate")
public class RuleEngineController {

    private final RuleEngine ruleEngine;

    public RuleEngineController(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Anomaly> evaluate(@RequestBody EventDto dto) {
        if (dto == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Optional<Anomaly> anomaly = ruleEngine.evaluate(dto.toSimpleEvent());
            return ResponseEntity.ok(anomaly.orElse(null));
        } catch (IllegalArgumentException e) {
            // 契约不兼容的非法输入：返回 400 而非 500，避免污染错误监控
            return ResponseEntity.badRequest().build();
        }
    }
}
