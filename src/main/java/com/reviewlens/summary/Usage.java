package com.reviewlens.summary;

/** Per-request cost and work report. Tokens come from the provider when it reports them. */
public record Usage(int llmCalls,
                    int chunks,
                    int repairs,
                    int inputTokens,
                    int outputTokens,
                    double estimatedCostUsd,
                    long latencyMs) {

    public static Usage none() {
        return new Usage(0, 0, 0, 0, 0, 0, 0);
    }
}
