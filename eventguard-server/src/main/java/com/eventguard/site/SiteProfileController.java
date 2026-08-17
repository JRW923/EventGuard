package com.eventguard.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventguard.auth.security.AuthPrincipal;
import com.eventguard.auth.security.RequirePermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 个人主页内容：
 *  GET /site-profile 公开（AuthFilter 放行 /site-profile，匿名访客可读）；
 *  PUT /site-profile 需 user:manage（管理员在控制台编辑保存，前端立即生效，无需重新部署）。
 */
@RestController
@RequestMapping("/site-profile")
public class SiteProfileController {

    private final SiteProfileRepository repository;
    private final ObjectMapper objectMapper;

    public SiteProfileController(SiteProfileRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Map<String, Object> get() {
        Optional<JsonNode> content = repository.findContent();
        // 未配置/畸形时 content=null，前端回落静态默认内容
        return Map.of("content", content.orElse(null));
    }

    @PutMapping
    @RequirePermission("user:manage")
    public ResponseEntity<Map<String, Object>> save(@RequestBody String rawBody,
                                                    org.springframework.web.context.request.WebRequest request) {
        JsonNode content;
        try {
            content = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "内容不是合法 JSON"));
        }
        if (!content.isObject()) {
            return ResponseEntity.badRequest().body(Map.of("error", "内容必须是 JSON 对象"));
        }
        String updatedBy = Optional.ofNullable(
                        (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTR, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST))
                .map(p -> p.getUsername() != null ? p.getUsername() : "unknown")
                .orElse("unknown");
        repository.saveContent(content, updatedBy);
        return ResponseEntity.ok(Map.of("content", content));
    }
}
