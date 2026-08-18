package com.eventguard.auth.controller;

import com.eventguard.auth.dto.UserLlmConfigView;
import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.service.UserLlmConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户自己的 LLM 配置（任何登录用户可读写，非管理员专属）。
 * 路径刻意不与 UserController 的 /users（user:manage）重合，避免类级权限拦截。
 */
@RestController
@RequestMapping("/users/me/llm-config")
public class UserLlmConfigController {

    private final UserLlmConfigService service;

    public UserLlmConfigController(UserLlmConfigService service) {
        this.service = service;
    }

    @GetMapping
    public UserLlmConfigView get(HttpServletRequest request) {
        return service.getMine(uid(request));
    }

    @PutMapping
    public UserLlmConfigView save(@RequestBody SaveRequest req, HttpServletRequest request) {
        return service.save(uid(request), req.provider(), req.base_url(), req.api_key(),
                req.model(), req.max_tokens() == null ? 2048 : req.max_tokens(),
                req.temperature() == null ? 0.3 : req.temperature());
    }

    private long uid(HttpServletRequest request) {
        AuthPrincipal p = AuthPrincipal.from(request);
        if (p.isMachine() || p.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "机器主体无用户 LLM 配置");
        }
        return p.getUserId();
    }

    public record SaveRequest(String provider, String base_url, String api_key, String model,
                              Integer max_tokens, Double temperature) {}
}
