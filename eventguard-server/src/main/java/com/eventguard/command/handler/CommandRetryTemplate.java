package com.eventguard.command.handler;

import com.eventguard.common.exception.OptimisticConcurrencyException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 命令重试模板：捕获 OptimisticConcurrencyException，重试最多 3 次（共 4 次尝试）。
 * 退避策略：线性 10ms × attempt。
 */
@Component
public class CommandRetryTemplate {

    public static final int MAX_RETRIES = 3;
    public static final long RETRY_DELAY_MS = 10;

    public <T> T executeWithRetry(Supplier<T> action) {
        OptimisticConcurrencyException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (OptimisticConcurrencyException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                }
            }
        }
        throw lastException;
    }
}
