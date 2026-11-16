package com.reviewlens.llm;

/**
 * Strategy interface: the service depends on this, never on a concrete vendor.
 * Implementations are picked at startup by the reviewlens.llm.provider property (LLM_PROVIDER env var).
 */
public interface LlmProvider {

    /** @throws LlmException on any failure (network, timeout, auth, bad response). */
    LlmResponse complete(String systemPrompt, String userPrompt);

    String name();
}
