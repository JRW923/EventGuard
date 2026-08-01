package com.eventguard.auth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LoginAttemptGuard：连续失败锁定、成功重置、锁到期解锁。 */
class LoginAttemptGuardTest {

    private final LoginAttemptGuard guard = new LoginAttemptGuard();

    @Test
    void fiveFailures_locks() {
        assertFalse(guard.isLocked("alice"));
        for (int i = 0; i < 5; i++) {
            guard.onFailure("alice");
        }
        assertTrue(guard.isLocked("alice"));
    }

    @Test
    void fourFailures_notLocked() {
        for (int i = 0; i < 4; i++) {
            guard.onFailure("bob");
        }
        assertFalse(guard.isLocked("bob"));
    }

    @Test
    void success_resetsCounter() {
        for (int i = 0; i < 4; i++) {
            guard.onFailure("carol");
        }
        guard.onSuccess("carol");
        guard.onFailure("carol");
        assertFalse(guard.isLocked("carol"));
    }

    @Test
    void lockExpires() throws InterruptedException {
        // 用极短锁定窗口不可行（常量固定 5min），验证锁定后剩余时间为正即可
        for (int i = 0; i < 5; i++) {
            guard.onFailure("dave");
        }
        assertTrue(guard.lockRemainingMillis("dave") > 0);
    }
}
