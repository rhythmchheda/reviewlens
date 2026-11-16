package com.reviewlens.summary;

import com.reviewlens.llm.LlmProvider;
import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import com.reviewlens.store.InMemoryStore;
import com.reviewlens.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SummaryService {

    private final InMemoryStore store;
    private final PromptBuilder promptBuilder;
    private final LlmProvider llm;

    public SummaryService(InMemoryStore store, PromptBuilder promptBuilder, LlmProvider llm) {
        this.store = store;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
    }

    public SummaryResponse summarize(long productId) {
        Product product = store.findProduct(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " not found"));
        List<Review> reviews = store.reviewsFor(productId);
        ReviewStats stats = ReviewStats.of(reviews);

        if (reviews.isEmpty()) {
            return new SummaryResponse(productId, product.name(), stats, "No reviews yet.", 0, 0, "none");
        }

        // TODO Milestone 2: dedupe + spam filter, map-reduce over chunks, JSON output, grounding check.
        Prompt prompt = promptBuilder.build(product, reviews);
        String summary = llm.complete(prompt.system(), prompt.user()); // LlmException -> 502
        return new SummaryResponse(productId, product.name(), stats, summary,
                prompt.reviewsIncluded(), prompt.reviewsOmitted(), llm.name());
    }
}
