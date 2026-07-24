package com.eventguard.compensation.controller;

import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import com.eventguard.compensation.service.CompensationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补偿执行 REST 接口（POST /compensations）。
 */
@RestController
@RequestMapping("/compensations")
public class CompensationController {

    private final CompensationService service;

    public CompensationController(CompensationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CompensationResult> execute(@RequestBody CompensationRequest request) {
        CompensationResult result = service.execute(request);
        return ResponseEntity.ok(result);
    }
}
