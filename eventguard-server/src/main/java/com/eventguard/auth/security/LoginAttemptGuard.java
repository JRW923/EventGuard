package com.eventguard.auth.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录防爆破：同一用户名连续失败 5 次锁定 5 分钟。
 * ponytail: 内存计数，仅单实例生效；升级路径=Redis 分布式计数 + IP 维度。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60_000L;

    private final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public boolean isLocked(String username) {
        Long until = lockedUntil.get(username);
        if (until == null) {
            return false;
        }
        if (until > System.currentTimeMillis()) {
            return true;
        }
        lockedUntil.remove(username);
        failures.remove(username);
        return false;
    }

    public long lockRemainingMillis(String username) {
        Long until = lockedUntil.get(username);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    public void onFailure(String username) {
        int count = failures.computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
        if (count >= MAX_FAILURES) {
            lockedUntil.put(username, System.currentTimeMillis() + LOCK_MILLIS);
        }
    }

    public void onSuccess(String username) {
        failures.remove(username);
        lockedUntil.remove(username);
    }
}
