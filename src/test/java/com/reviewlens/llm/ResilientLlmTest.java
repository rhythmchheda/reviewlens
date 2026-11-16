package com.reviewlens.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientLlmTest {

    /** Each queued item is either an exception to throw or null for success. */
    static class FlakyProvider implements LlmProvider {
        final Deque<LlmException> script;
        int calls;

        FlakyProvider(LlmException... failures) {
            this.script = new ArrayDeque<>(List.of(failures));
        }

        @Override
        public LlmResponse complete(String systemPrompt, String userPrompt) {
            calls++;
            if (!script.isEmpty()) {
                throw script.poll();
            }
            return new LlmResponse("{}", 1, 1);
        }

        @Override
        public String name() {
            return "flaky";
        }
    }

    private static CircuitBreaker breaker(int threshold) {
        return new CircuitBreaker(threshold, Duration.ofSeconds(30), Clock.systemUTC());
    }

    @Test
    void retriesTransientFailures() {
        FlakyProvider p = new FlakyProvider(new LlmException("503"), new LlmException("timeout"));
        LlmResponse r = new ResilientLlm(p, breaker(10), 3, 1).complete("s", "u");

        assertThat(r.text()).isEqualTo("{}");
        assertThat(p.calls).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        FlakyProvider p = new FlakyProvider(new LlmException("a"), new LlmException("b"), new LlmException("c"));
        assertThatThrownBy(() -> new ResilientLlm(p, breaker(10), 3, 1).complete("s", "u"))
                .hasMessage("c");
        assertThat(p.calls).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetryableErrors() {
        FlakyProvider p = new FlakyProvider(new LlmException("401 bad key", null, false));
        assertThatThrownBy(() -> new ResilientLlm(p, breaker(10), 3, 1).complete("s", "u"))
                .hasMessage("401 bad key");
        assertThat(p.calls).isEqualTo(1);
    }

    @Test
    void openCircuitFailsFastWithoutCallingProvider() {
        FlakyProvider p = new FlakyProvider(new LlmException("a"), new LlmException("b"));
        ResilientLlm llm = new ResilientLlm(p, breaker(2), 3, 1);

        assertThatThrownBy(() -> llm.complete("s", "u")).hasMessageStartingWith("circuit open");
        assertThat(p.calls).isEqualTo(2); // third attempt was blocked by the breaker
        assertThat(llm.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> llm.complete("s", "u")).hasMessageStartingWith("circuit open");
        assertThat(p.calls).isEqualTo(2);
    }

    @Test
    void backoffGrowsExponentiallyWithJitter() {
        ResilientLlm llm = new ResilientLlm(new FlakyProvider(), breaker(10), 3, 100);
        assertThat(llm.backoffMs(1)).isBetween(100L, 200L);
        assertThat(llm.backoffMs(2)).isBetween(200L, 300L);
        assertThat(llm.backoffMs(3)).isBetween(400L, 500L);
    }
}
