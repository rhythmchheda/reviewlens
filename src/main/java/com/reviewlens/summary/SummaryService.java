package com.reviewlens.summary;

import com.reviewlens.filter.FilterPipeline;
import com.reviewlens.filter.FilterResult;
import com.reviewlens.llm.LlmException;
import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import com.reviewlens.store.InMemoryStore;
import com.reviewlens.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates: cache lookup -> filter -> stats (in code) -> map-reduce summary -> grounding check.
 * On LLM failure, degrades to the last good summary (marked stale) instead of an error.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final InMemoryStore store;
    private final FilterPipeline filters;
    private final Summarizer summarizer;
    private final SummaryCache cache;
    private final SummaryMetrics metrics;

    public SummaryService(InMemoryStore store, FilterPipeline filters, Summarizer summarizer,
                          SummaryCache cache, SummaryMetrics metrics) {
        this.store = store;
        this.filters = filters;
        this.summarizer = summarizer;
        this.cache = cache;
        this.metrics = metrics;
    }

    public SummaryResponse summarize(long productId, boolean refresh) {
        Product product = store.findProduct(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " not found"));
        List<Review> all = store.reviewsFor(productId);

        try {
            SummaryCache.Lookup lookup = cache.getOrCompute(productId, SummaryCache.key(all), refresh,
                    () -> compute(product, all));
            metrics.cache(lookup.hit() ? "hit" : "miss");
            return lookup.hit() ? lookup.response().withFlags(true, false) : lookup.response();
        } catch (LlmException e) {
            Optional<SummaryResponse> previous = cache.lastGood(productId);
            if (previous.isPresent()) {
                log.warn("Serving stale summary for product {}: {}", productId, e.getMessage());
                metrics.cache("stale");
                return previous.get().withFlags(true, true);
            }
            metrics.failure();
            throw e;
        }
    }

    private SummaryResponse compute(Product product, List<Review> all) {
        FilterResult filtered = filters.apply(all);
        SummaryResponse.Filtering filtering =
                new SummaryResponse.Filtering(filtered.countsByFilter(), filtered.rejected());
        ReviewStats rawStats = ReviewStats.of(all);
        ReviewStats stats = ReviewStats.of(filtered.kept());

        if (filtered.kept().isEmpty()) {
            return new SummaryResponse(product.id(), product.name(), stats, rawStats, filtering,
                    null, SummaryResponse.Grounding.none(), Usage.none(), "none", false, false);
        }

        Summarizer.Result result = summarizer.summarize(product, filtered.kept());
        SummaryResponse response = new SummaryResponse(product.id(), product.name(), stats, rawStats, filtering,
                result.summary(), SummaryResponse.Grounding.of(result.grounding()), result.usage(),
                summarizer.providerName(), false, false);
        metrics.recordComputed(response);
        return response;
    }
}
