package com.eventguard.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 签发与解析（HS256）。claims 含 uid/username/displayName/roles/permissions/mcp(mustChangePassword)，
 * 供后端权限拦截、WS 握手校验与 AI 服务（PyJWT）共用同一 secret（EG_JWT_SECRET）。
 *
 * ponytail: 权限放 claims，角色/权限变更需重新登录生效（无刷新令牌/吊销）；升级路径=引入 refresh token + jti 黑名单。
 */
@Component
public class JwtService {

    // 默认密钥须 ≥32 字节（HS256 要求）；生产必须用 EG_JWT_SECRET 注入强随机值
    private static final String DEFAULT_SECRET = "eventguard-dev-secret-change-me-0123456789abcdef";
    private static final long DEFAULT_EXPIRE_MINUTES = 720; // 12h

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(@Value("${EG_JWT_SECRET:" + DEFAULT_SECRET + "}") String secret,
                      @Value("${EG_JWT_EXPIRE_MINUTES:" + DEFAULT_EXPIRE_MINUTES + "}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    public String issue(long userId, String username, String displayName,
                        List<String> roles, List<String> permissions, boolean mustChangePassword) {
        Instant now = Instant.now();
        // ponytail: 显式固定 HS256——jjwt 会按密钥长度推断算法（32B→HS256/48B→HS384），
        // 而 AI 侧 PyJWT 固定校验 HS256，避免密钥变长后两端算法不一致
        return Jwts.builder()
                .subject(username)
                .claims(Map.of(
                        "uid", userId,
                        "username", username,
                        "displayName", displayName == null ? username : displayName,
                        "roles", roles,
                        "permissions", permissions,
                        "mcp", mustChangePassword))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expireMillis)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验签名/过期；非法或过期抛 JwtException，由调用方转 401。 */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    public static Long uid(Claims c) {
        Object uid = c.get("uid");
        return uid instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(uid));
    }

    @SuppressWarnings("unchecked")
    public static List<String> strings(Claims c, String name) {
        Object v = c.get(name);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }
}
