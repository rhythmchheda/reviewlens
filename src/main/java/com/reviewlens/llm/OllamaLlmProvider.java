package com.reviewlens.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Free, local LLM. Install Ollama, run `ollama pull llama3.2`, then LLM_PROVIDER=ollama. */
@Component
@ConditionalOnProperty(name = "reviewlens.llm.provider", havingValue = "ollama")
public class OllamaLlmProvider implements LlmProvider {

    private final RestClient client;
    private final String model;

    public OllamaLlmProvider(RestClient.Builder builder,
                             @Value("${reviewlens.llm.ollama.base-url}") String baseUrl,
                             @Value("${reviewlens.llm.ollama.model}") String model) {
        this.model = model;
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            Map<String, Object> resp = client.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> message = (Map<String, Object>) resp.get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new LlmException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }
}
