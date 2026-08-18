package com.eventguard.auth.dto;

import com.eventguard.auth.model.UserLlmConfig;

/** 用户 LLM 配置公开视图：不下发明文 API key，只返回掩码与是否存在。 */
public record UserLlmConfigView(String provider, String baseUrl, String model,
                                int maxTokens, double temperature,
                                String apiKeyMasked, boolean hasApiKey) {

    public static UserLlmConfigView from(UserLlmConfig c, String decryptedKey) {
        return new UserLlmConfigView(
                c.getProvider(), c.getBaseUrl(), c.getModel(),
                c.getMaxTokens(), c.getTemperature(),
                mask(decryptedKey), !decryptedKey.isEmpty());
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() <= 4) {
            return "****";
        }
        return "*".repeat(key.length() - 4) + key.substring(key.length() - 4);
    }
}
