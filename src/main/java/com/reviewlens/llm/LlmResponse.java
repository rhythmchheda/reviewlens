package com.reviewlens.llm;

import java.util.Map;

/** Model output plus token usage (from the provider when it reports it, otherwise estimated). */
public record LlmResponse(String text, int inputTokens, int outputTokens) {

    static int intOr(Object map, String key, int fallback) {
        if (map instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }
}
