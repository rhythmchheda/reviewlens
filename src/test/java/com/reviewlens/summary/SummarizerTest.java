package com.reviewlens.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewlens.llm.CircuitBreaker;
import com.reviewlens.llm.LlmProvider;
import com.reviewlens.llm.ResilientLlm;
import com.reviewlens.llm.LlmResponse;
import com.reviewlens.llm.MockLlmProvider;
import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.reviewlens.TestReviews.review;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummarizerTest {

    private static final String VALID = """
            {"sentiment":"positive","overview":"Liked.","pros":["sound"],"cons":[],"quotes":[]}""";

    private final ObjectMapper json = new ObjectMapper();
    private final Product product = new Product(1, "Earbuds");

    /** Returns canned replies in order. */
    static class ScriptedLlm implements LlmProvider {
        final Deque<String> replies;
        int calls;

        ScriptedLlm(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public LlmResponse complete(String systemPrompt, String userPrompt) {
            calls++;
            return new LlmResponse(replies.isEmpty() ? VALID : replies.poll(), 100, 20);
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    private Summarizer summarizer(LlmProvider llm) {
        ResilientLlm resilient = new ResilientLlm(llm,
                new CircuitBreaker(100, Duration.ofSeconds(1), Clock.systemUTC()), 1, 0);
        return new Summarizer(resilient, new PromptBuilder(3000, 1000, 10, json), new SummaryParser(json),
                new GroundingChecker(), 4, 0.15, 0.60);
    }

    @Test
    void repairsInvalidJsonOnce() {
        ScriptedLlm llm = new ScriptedLlm("Sure! Customers love it.", VALID);
        Summarizer.Result r = summarizer(llm).summarize(product, List.of(review("a", 5, "Great sound.")));

        assertThat(r.summary().pros()).containsExactly("sound");
        assertThat(r.usage().llmCalls()).isEqualTo(2);
        assertThat(r.usage().repairs()).isEqualTo(1);
    }

    @Test
    void givesUpAfterSecondInvalidReply() {
        ScriptedLlm llm = new ScriptedLlm("nope", "{\"sentiment\":\"amazing\",\"overview\":\"x\"}");
        assertThatThrownBy(() -> summarizer(llm).summarize(product, List.of(review("a", 5, "Great."))))
                .isInstanceOf(InvalidSummaryException.class);
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    void mapReducesLargeInputAndDropsHallucinatedQuote() {
        List<Review> reviews = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            reviews.add(review("u" + i, i % 5 + 1, "Review " + i + " says the battery is fine, but the strap is stiff."));
        }
        Summarizer.Result r = summarizer(new MockLlmProvider(json, true)).summarize(product, reviews);

        assertThat(r.usage().chunks()).isEqualTo(3);
        assertThat(r.usage().llmCalls()).isEqualTo(4); // 3 map + 1 reduce
        assertThat(r.grounding().dropped())
                .extracting(GroundingChecker.DroppedQuote::text)
                .contains(MockLlmProvider.FABRICATED_QUOTE);
        assertThat(r.summary().quotes()).noneMatch(q -> q.text().equals(MockLlmProvider.FABRICATED_QUOTE));
    }

    @Test
    void reportsCost() {
        Summarizer.Result r = summarizer(new ScriptedLlm()).summarize(product, List.of(review("a", 5, "Great.")));
        // 100 input tokens * $0.15/M + 20 output * $0.60/M = $0.000027
        assertThat(r.usage().estimatedCostUsd()).isEqualTo(0.000027);
    }
}
