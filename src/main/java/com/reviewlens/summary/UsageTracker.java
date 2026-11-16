package com.reviewlens.summary;

import com.reviewlens.llm.LlmResponse;

import java.util.concurrent.atomic.AtomicInteger;

/** Collects usage for one summary request. Thread-safe because map calls run in parallel. */
final class UsageTracker {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger repairs = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();

    void record(LlmResponse response) {
        calls.incrementAndGet();
        inputTokens.addAndGet(response.inputTokens());
        outputTokens.addAndGet(response.outputTokens());
    }

    void repair() {
        repairs.incrementAndGet();
    }

    Usage snapshot(int chunks, long latencyMs, double inputPricePerMillion, double outputPricePerMillion) {
        double cost = (inputTokens.get() * inputPricePerMillion + outputTokens.get() * outputPricePerMillion) / 1_000_000;
        return new Usage(calls.get(), chunks, repairs.get(), inputTokens.get(), outputTokens.get(),
                Math.round(cost * 1_000_000) / 1_000_000.0, latencyMs);
    }
}
