package com.eventguard.auth.controller;

import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.service.UserLlmConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 内部端点：AI 服务（机器主体）按 uid 拉取用户解密后的完整 LLM 配置。
 * 仅机器密钥（EG_MACHINE_API_KEY）可访问；用户 JWT 一律 403，避免越权读他人配置。
 */
@RestController
@RequestMapping("/internal/users")
public class InternalLlmConfigController {

    private final UserLlmConfigService service;

    public InternalLlmConfigController(UserLlmConfigService service) {
        this.service = service;
    }

    @GetMapping("/{uid}/llm-config")
    public Map<String, Object> get(@PathVariable long uid, HttpServletRequest request) {
        AuthPrincipal p = AuthPrincipal.from(request);
        if (!p.isMachine()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅内部服务可访问");
        }
        return service.getDecrypted(uid);
    }
}
