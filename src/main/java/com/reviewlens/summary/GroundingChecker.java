package com.reviewlens.summary;

import com.reviewlens.model.Review;
import com.reviewlens.text.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Output validation: every quote must really appear in the review it cites.
 * Comparison is on normalized text (case and punctuation ignored) and on whole words, so
 * "batter" does not match "battery". Ungrounded quotes are removed and reported, never shown.
 *
 * Pros/cons are paraphrases, so exact matching does not apply to them. Next step: an LLM-as-judge
 * or entailment check that each pro/con is supported by at least one review.
 */
@Component
public class GroundingChecker {

    public record DroppedQuote(long reviewId, String text, String reason) {
    }

    public record Result(ReviewSummary summary, int quotesChecked, List<DroppedQuote> dropped) {
    }

    public Result check(ReviewSummary summary, Collection<Review> reviews) {
        Map<Long, String> paddedTextById = reviews.stream().collect(Collectors.toMap(
                Review::id, r -> " " + TextNormalizer.normalize(r.text()) + " ", (a, b) -> a));

        List<ReviewSummary.Quote> kept = new ArrayList<>();
        List<DroppedQuote> dropped = new ArrayList<>();
        for (ReviewSummary.Quote q : summary.quotes()) {
            String source = paddedTextById.get(q.reviewId());
            String needle = TextNormalizer.normalize(TextNormalizer.unescapeHtml(q.text()));
            if (source == null) {
                dropped.add(new DroppedQuote(q.reviewId(), q.text(), "cites a review that is not in the input"));
            } else if (needle.isEmpty()) {
                dropped.add(new DroppedQuote(q.reviewId(), q.text(), "empty quote"));
            } else if (!source.contains(" " + needle + " ")) {
                dropped.add(new DroppedQuote(q.reviewId(), q.text(), "text not found in the cited review"));
            } else {
                kept.add(q);
            }
        }
        return new Result(summary.withQuotes(kept), summary.quotes().size(), dropped);
    }
}
