package com.reviewlens.summary;

import com.reviewlens.filter.RejectedReview;

import java.util.List;
import java.util.Map;

/**
 * @param stats     computed over the reviews that passed filtering
 * @param rawStats  computed over all reviews, so the effect of filtering is visible
 * @param summary   null when no usable reviews remain
 */
public record SummaryResponse(long productId,
                              String productName,
                              ReviewStats stats,
                              ReviewStats rawStats,
                              Filtering filtering,
                              ReviewSummary summary,
                              Grounding grounding,
                              Usage usage,
                              String provider) {

    public record Filtering(Map<String, Long> counts, List<RejectedReview> rejected) {
    }

    public record Grounding(int quotesChecked, int quotesDropped, List<GroundingChecker.DroppedQuote> dropped) {

        static Grounding of(GroundingChecker.Result r) {
            return new Grounding(r.quotesChecked(), r.dropped().size(), r.dropped());
        }

        static Grounding none() {
            return new Grounding(0, 0, List.of());
        }
    }
}
