package com.eventguard.auth.model;

/** 用户级 LLM 配置实体（apiKeyEnc 为加密后的 API key，对外只暴露掩码）。 */
public class UserLlmConfig {

    private Long userId;
    private String provider = "";
    private String baseUrl;
    private String apiKeyEnc = "";
    private String model;
    private int maxTokens = 2048;
    private double temperature = 0.3;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}
