package com.reviewlens.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "reviewlens.llm.provider", havingValue = "openai")
public class OpenAiLlmProvider implements LlmProvider {

    private final RestClient client;
    private final String model;

    public OpenAiLlmProvider(RestClient.Builder builder,
                             @Value("${reviewlens.llm.openai.api-key}") String apiKey,
                             @Value("${reviewlens.llm.openai.model}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM_PROVIDER=openai requires OPENAI_API_KEY");
        }
        this.model = model;
        this.client = builder.baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            Map<String, Object> resp = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new LlmException("OpenAI call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "openai:" + model;
    }
}
