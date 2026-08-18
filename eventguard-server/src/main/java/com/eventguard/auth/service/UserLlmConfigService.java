package com.eventguard.auth.service;

import com.eventguard.auth.dto.UserLlmConfigView;
import com.eventguard.auth.model.UserLlmConfig;
import com.eventguard.auth.repository.UserLlmConfigRepository;
import com.eventguard.common.security.SecretCipher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 用户级 LLM 配置：每个用户读写自己的 base_url/api_key/model，API key 加密存储、对外只回掩码。 */
@Service
public class UserLlmConfigService {

    private static final Set<String> PROVIDERS = Set.of("", "openai", "anthropic");

    private final UserLlmConfigRepository repo;
    private final SecretCipher cipher;

    public UserLlmConfigService(UserLlmConfigRepository repo, SecretCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /** 当前用户自己的配置视图（未配置时 base_url 为空、hasApiKey=false）。 */
    public UserLlmConfigView getMine(long uid) {
        return repo.findByUserId(uid)
                .map(c -> UserLlmConfigView.from(c, cipher.decrypt(c.getApiKeyEnc())))
                .orElseGet(() -> new UserLlmConfigView("", "", "", 2048, 0.3, "", false));
    }

    /** 保存自己的配置；api_key 为空表示保留现有 key。 */
    public UserLlmConfigView save(long uid, String provider, String baseUrl, String apiKey,
                                  String model, int maxTokens, double temperature) {
        validate(provider, baseUrl, model, maxTokens, temperature);
        UserLlmConfig existing = repo.findByUserId(uid).orElse(null);
        String apiKeyEnc = (apiKey != null && !apiKey.isBlank())
                ? cipher.encrypt(apiKey.trim())
                : (existing != null ? existing.getApiKeyEnc() : "");
        UserLlmConfig c = new UserLlmConfig();
        c.setUserId(uid);
        c.setProvider(provider == null ? "" : provider.trim().toLowerCase());
        c.setBaseUrl(baseUrl.trim());
        c.setApiKeyEnc(apiKeyEnc);
        c.setModel(model.trim());
        c.setMaxTokens(maxTokens);
        c.setTemperature(temperature);
        repo.upsert(c);
        return UserLlmConfigView.from(c, cipher.decrypt(apiKeyEnc));
    }

    /** 内部端点专用：返回解密后的完整配置（含明文 api_key），供 AI 服务机器主体调用。 */
    public Map<String, Object> getDecrypted(long uid) {
        UserLlmConfig c = repo.findByUserId(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户未配置 LLM"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", c.getProvider());
        m.put("base_url", c.getBaseUrl());
        m.put("api_key", cipher.decrypt(c.getApiKeyEnc()));
        m.put("model", c.getModel());
        m.put("max_tokens", c.getMaxTokens());
        m.put("temperature", c.getTemperature());
        return m;
    }

    private void validate(String provider, String baseUrl, String model, int maxTokens, double temperature) {
        if (provider != null && !PROVIDERS.contains(provider.trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider 仅支持 openai、anthropic 或自动探测");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base_url 不能为空");
        }
        String url = baseUrl.trim().replaceAll("/+$", "");
        try {
            URI uri = new URI(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base_url 必须以 http:// 或 https:// 开头");
            }
            if (uri.getUserInfo() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base_url 不应包含账号或密码，请单独填写 API key");
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base_url 格式非法");
        }
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model 不能为空");
        }
        if (maxTokens < 128 || maxTokens > 32768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max_tokens 需在 128~32768 之间");
        }
        if (temperature < 0 || temperature > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "temperature 需在 0~2 之间");
        }
    }
}
