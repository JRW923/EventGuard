package com.eventguard.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 仅在生产环境阻止开发密钥启动；不会修改或禁用三种演示账号。
 * <p>
 * 非 prod 环境仍允许使用开发默认密钥（保证 `docker compose up` 开箱可演示），
 * 但会在启动时打 WARN——避免 EG_ENV 忘记设置就把默认密钥带上线而毫无提示。
 * <p>
 * 本类同时是两个开发默认密钥的<b>唯一权威定义处</b>，AuthFilter 与
 * GatewayCallbackController 的 @Value fallback 必须引用这里的常量，不要各写字面量。
 */
@Component
public class ProductionSecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecurityGuard.class);

    public static final String DEFAULT_JWT_SECRET = "eventguard-dev-secret-change-me-0123456789abcdef";
    public static final String DEFAULT_MACHINE_KEY = "dev-machine-key";

    private final String environment;
    private final String jwtSecret;
    private final String machineKey;
    private final boolean callbackSignatureRequired;
    private final String callbackSecret;
    private final String wsAllowedOrigins;
    private final boolean requireCommandId;
    private final boolean allowBodyUserId;
    private final String sqlInitMode;

    public ProductionSecurityGuard(
            @Value("${EG_ENV:demo}") String environment,
            @Value("${EG_JWT_SECRET:" + DEFAULT_JWT_SECRET + "}") String jwtSecret,
            @Value("${EG_MACHINE_API_KEY:" + DEFAULT_MACHINE_KEY + "}") String machineKey,
            @Value("${EG_GATEWAY_CALLBACK_SIGNATURE_REQUIRED:false}") boolean callbackSignatureRequired,
            @Value("${EG_GATEWAY_CALLBACK_SECRET:}") String callbackSecret,
            @Value("${EG_WS_ALLOWED_ORIGINS:*}") String wsAllowedOrigins,
            @Value("${EG_REQUIRE_COMMAND_ID:false}") boolean requireCommandId,
            @Value("${EG_ALLOW_BODY_USER_ID:true}") boolean allowBodyUserId,
            @Value("${EG_SQL_INIT_MODE:always}") String sqlInitMode) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.machineKey = machineKey;
        this.callbackSignatureRequired = callbackSignatureRequired;
        this.callbackSecret = callbackSecret;
        this.wsAllowedOrigins = wsAllowedOrigins;
        this.requireCommandId = requireCommandId;
        this.allowBodyUserId = allowBodyUserId;
        this.sqlInitMode = sqlInitMode;
    }

    @PostConstruct
    void validate() {
        if (!"prod".equalsIgnoreCase(environment) && !"production".equalsIgnoreCase(environment)) {
            warnIfDefaultSecrets();
            return;
        }
        if (jwtSecret == null || jwtSecret.isBlank() || DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("生产环境必须设置 EG_JWT_SECRET");
        }
        if (machineKey == null || machineKey.isBlank() || DEFAULT_MACHINE_KEY.equals(machineKey)) {
            throw new IllegalStateException("生产环境必须设置 EG_MACHINE_API_KEY");
        }
        if (callbackSignatureRequired && (callbackSecret == null || callbackSecret.isBlank())) {
            throw new IllegalStateException("开启回调签名校验时必须设置 EG_GATEWAY_CALLBACK_SECRET");
        }
        if (wsAllowedOrigins == null || wsAllowedOrigins.isBlank() || wsAllowedOrigins.contains("*")) {
            throw new IllegalStateException("生产环境必须设置 EG_WS_ALLOWED_ORIGINS，禁止通配 Origin");
        }
        if (!requireCommandId) {
            throw new IllegalStateException("生产环境必须开启 EG_REQUIRE_COMMAND_ID");
        }
        if (allowBodyUserId) {
            throw new IllegalStateException("生产环境必须关闭 EG_ALLOW_BODY_USER_ID");
        }
        if ("always".equalsIgnoreCase(sqlInitMode)) {
            throw new IllegalStateException("生产环境必须关闭 spring.sql.init.mode=always");
        }
    }

    /** EG_ENV 未设为 prod 时不阻止启动，但默认密钥必须留下痕迹，否则忘记配置就会静默上线。 */
    private void warnIfDefaultSecrets() {
        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            log.warn("[安全] 正在使用开发默认 EG_JWT_SECRET（EG_ENV={}）。对外提供服务前必须改为强随机值。", environment);
        }
        if (DEFAULT_MACHINE_KEY.equals(machineKey)) {
            log.warn("[安全] 正在使用开发默认 EG_MACHINE_API_KEY（EG_ENV={}）。对外提供服务前必须改为强随机值。", environment);
        }
    }
}
