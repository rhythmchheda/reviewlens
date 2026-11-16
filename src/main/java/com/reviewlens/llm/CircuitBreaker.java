package com.reviewlens.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Minimal circuit breaker (State pattern, hand-written so every line can be explained).
 *
 *   CLOSED    - calls flow; N consecutive failures -> OPEN
 *   OPEN      - calls fail fast without touching the provider; after openDuration -> HALF_OPEN
 *   HALF_OPEN - exactly one trial call; success -> CLOSED, failure -> OPEN again
 *
 * Why: when the provider is down, retrying every request just adds latency and load. Failing fast
 * lets us serve a cached summary immediately. In production you would likely use Resilience4j.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** @return true if the caller may make a call now */
    public synchronized boolean tryAcquire() {
        return switch (state) {
            case CLOSED -> true;
            case HALF_OPEN -> false; // a trial call is already in flight
            case OPEN -> {
                if (clock.instant().isBefore(openedAt.plus(openDuration))) {
                    yield false;
                }
                state = State.HALF_OPEN;
                yield true;
            }
        };
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
            consecutiveFailures = 0;
        }
    }

    public synchronized State state() {
        return state;
    }
}
