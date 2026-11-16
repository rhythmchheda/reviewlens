package com.reviewlens.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Deterministic, offline provider. Default, so the app runs with no API key. */
@Component
@ConditionalOnProperty(name = "reviewlens.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmProvider implements LlmProvider {

    private static final Pattern REVIEW_TAG = Pattern.compile("<review ");

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        long n = REVIEW_TAG.matcher(userPrompt).results().count();
        return "[mock] Summary of " + n + " reviews: customers mention sound quality and battery life; "
                + "some complain about fit. Set LLM_PROVIDER=openai or ollama for a real summary.";
    }

    @Override
    public String name() {
        return "mock";
    }
}
