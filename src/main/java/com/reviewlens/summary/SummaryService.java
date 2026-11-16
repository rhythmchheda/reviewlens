package com.reviewlens.summary;

import com.reviewlens.filter.FilterPipeline;
import com.reviewlens.filter.FilterResult;
import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import com.reviewlens.store.InMemoryStore;
import com.reviewlens.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Orchestrates: load -> filter -> stats (in code) -> map-reduce summary -> grounding check. */
@Service
public class SummaryService {

    private final InMemoryStore store;
    private final FilterPipeline filters;
    private final Summarizer summarizer;

    public SummaryService(InMemoryStore store, FilterPipeline filters, Summarizer summarizer) {
        this.store = store;
        this.filters = filters;
        this.summarizer = summarizer;
    }

    public SummaryResponse summarize(long productId) {
        Product product = store.findProduct(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " not found"));
        List<Review> all = store.reviewsFor(productId);

        FilterResult filtered = filters.apply(all);
        SummaryResponse.Filtering filtering =
                new SummaryResponse.Filtering(filtered.countsByFilter(), filtered.rejected());
        ReviewStats rawStats = ReviewStats.of(all);
        ReviewStats stats = ReviewStats.of(filtered.kept());

        if (filtered.kept().isEmpty()) {
            return new SummaryResponse(productId, product.name(), stats, rawStats, filtering,
                    null, SummaryResponse.Grounding.none(), Usage.none(), "none");
        }

        Summarizer.Result result = summarizer.summarize(product, filtered.kept());
        return new SummaryResponse(productId, product.name(), stats, rawStats, filtering,
                result.summary(), SummaryResponse.Grounding.of(result.grounding()), result.usage(),
                summarizer.providerName());
    }
}
