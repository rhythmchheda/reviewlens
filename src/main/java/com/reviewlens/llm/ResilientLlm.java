package com.reviewlens.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorator around the configured LlmProvider: retry with exponential backoff + jitter, guarded by a
 * circuit breaker. Timeouts live in the HTTP client of each provider.
 *
 * Jitter matters: without it, many clients that failed together retry together (thundering herd).
 */
@Component
public class ResilientLlm {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlm.class);

    private final LlmProvider delegate;
    private final CircuitBreaker breaker;
    private final int maxAttempts;
    private final long initialBackoffMs;

    @Autowired
    public ResilientLlm(LlmProvider delegate,
                        @Value("${reviewlens.llm.retry.max-attempts}") int maxAttempts,
                        @Value("${reviewlens.llm.retry.initial-backoff-ms}") long initialBackoffMs,
                        @Value("${reviewlens.llm.circuit.failure-threshold}") int failureThreshold,
                        @Value("${reviewlens.llm.circuit.open-seconds}") int openSeconds) {
        this(delegate, new CircuitBreaker(failureThreshold, Duration.ofSeconds(openSeconds), Clock.systemUTC()),
                maxAttempts, initialBackoffMs);
    }

    public ResilientLlm(LlmProvider delegate, CircuitBreaker breaker, int maxAttempts, long initialBackoffMs) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
    }

    public LlmResponse complete(String systemPrompt, String userPrompt) {
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.tryAcquire()) {
                throw new LlmException("circuit open: skipping call to " + delegate.name(), last, false);
            }
            try {
                LlmResponse response = delegate.complete(systemPrompt, userPrompt);
                breaker.onSuccess();
                return response;
            } catch (LlmException e) {
                breaker.onFailure();
                last = e;
                if (!e.retryable() || attempt == maxAttempts) {
                    break;
                }
                long backoff = backoffMs(attempt);
                log.info("LLM attempt {}/{} failed ({}), retrying in {} ms", attempt, maxAttempts, e.getMessage(), backoff);
                sleep(backoff);
            }
        }
        throw last;
    }

    public String name() {
        return delegate.name();
    }

    public CircuitBreaker.State circuitState() {
        return breaker.state();
    }

    /** 1x, 2x, 4x ... the initial backoff, plus up to 1x random jitter. */
    long backoffMs(int attempt) {
        long base = initialBackoffMs * (1L << (attempt - 1));
        return base + ThreadLocalRandom.current().nextLong(initialBackoffMs + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted during retry backoff", e, false);
        }
    }
}
