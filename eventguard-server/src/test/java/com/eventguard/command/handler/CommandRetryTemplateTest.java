package com.eventguard.command.handler;

import com.eventguard.common.exception.OptimisticConcurrencyException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRetryTemplateTest {

    CommandRetryTemplate template = new CommandRetryTemplate();

    @Test
    void executeWithRetry_should_return_on_first_success() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> action = () -> { calls.incrementAndGet(); return "ok"; };

        String result = template.executeWithRetry(action);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void executeWithRetry_should_retry_on_OCC_then_succeed() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> action = () -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticConcurrencyException("conflict");
            }
            return "ok";
        };

        String result = template.executeWithRetry(action);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void executeWithRetry_should_throw_after_3_retries() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> action = () -> {
            calls.incrementAndGet();
            throw new OptimisticConcurrencyException("always conflict");
        };

        assertThatThrownBy(() -> template.executeWithRetry(action))
                .isInstanceOf(OptimisticConcurrencyException.class);
        // 1 initial + 3 retries = 4 attempts
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void executeWithRetry_should_not_retry_non_occ_exception() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> action = () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("other error");
        };

        assertThatThrownBy(() -> template.executeWithRetry(action))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
