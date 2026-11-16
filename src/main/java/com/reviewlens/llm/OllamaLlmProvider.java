package com.reviewlens.llm;

import com.reviewlens.summary.TokenBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Free, local LLM. Install Ollama, run `ollama pull llama3.2`, then set LLM_PROVIDER=ollama. */
@Component
@ConditionalOnProperty(name = "reviewlens.llm.provider", havingValue = "ollama")
public class OllamaLlmProvider implements LlmProvider {

    private final RestClient client;
    private final String model;

    public OllamaLlmProvider(RestClient.Builder builder,
                             @Value("${reviewlens.llm.ollama.base-url}") String baseUrl,
                             @Value("${reviewlens.llm.ollama.model}") String model,
                             @Value("${reviewlens.llm.timeout-seconds}") int timeoutSeconds) {
        this.model = model;
        this.client = builder.baseUrl(baseUrl)
                .requestFactory(HttpClients.withTimeouts(timeoutSeconds))
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "format", "json",
                "options", Map.of("temperature", 0.2),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            Map<String, Object> resp = client.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            String text = (String) ((Map<String, Object>) resp.get("message")).get("content");
            return new LlmResponse(text,
                    LlmResponse.intOr(resp, "prompt_eval_count", TokenBudget.estimate(systemPrompt + userPrompt)),
                    LlmResponse.intOr(resp, "eval_count", TokenBudget.estimate(text)));
        } catch (Exception e) {
            throw new LlmException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }
}
