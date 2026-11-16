package com.reviewlens.filter;

import com.reviewlens.model.Review;
import com.reviewlens.text.TextNormalizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drops exact and near duplicates, keeping the earliest copy.
 *
 * Rules (tuned for precision: hiding a genuine review is worse than missing a duplicate):
 *   - exact match on normalized text: same author always; different authors only if text is long
 *     (two people can both honestly write "Great product!")
 *   - near match on word-set Jaccard similarity, only for reviews with 8+ distinct words:
 *     >= 0.8 for the same author, >= 0.9 across authors
 *
 * O(n^2) comparisons, fine for one product's reviews. At scale: MinHash + LSH to find candidate pairs.
 */
@Component
@Order(30)
public class DuplicateFilter implements ReviewFilter {

    static final int MIN_CHARS_CROSS_AUTHOR_EXACT = 40;
    static final int MIN_WORDS_NEAR_DUPLICATE = 8;
    static final double SAME_AUTHOR_SIMILARITY = 0.8;
    static final double CROSS_AUTHOR_SIMILARITY = 0.9;

    private record Seen(Review review, String author, String text, Set<String> words) {
    }

    @Override
    public String name() {
        return "duplicate";
    }

    @Override
    public FilterResult apply(List<Review> reviews) {
        List<Review> oldestFirst = reviews.stream()
                .sorted(Comparator.comparing(Review::createdAt).thenComparingLong(Review::id))
                .toList();

        List<Seen> seen = new ArrayList<>();
        Map<Long, String> rejectedReasons = new LinkedHashMap<>();
        for (Review r : oldestFirst) {
            Seen current = new Seen(r, TextNormalizer.normalize(r.author()),
                    TextNormalizer.normalize(r.text()), TextNormalizer.words(r.text()));
            String reason = null;
            for (Seen earlier : seen) {
                reason = compare(current, earlier);
                if (reason != null) {
                    break;
                }
            }
            if (reason == null) {
                seen.add(current);
            } else {
                rejectedReasons.put(r.id(), reason);
            }
        }

        List<Review> kept = reviews.stream().filter(r -> !rejectedReasons.containsKey(r.id())).toList();
        List<RejectedReview> rejected = reviews.stream()
                .filter(r -> rejectedReasons.containsKey(r.id()))
                .map(r -> new RejectedReview(r.id(), r.author(), name(), rejectedReasons.get(r.id())))
                .toList();
        return new FilterResult(kept, rejected);
    }

    private String compare(Seen current, Seen earlier) {
        boolean sameAuthor = current.author().equals(earlier.author());
        if (!current.text().isEmpty() && current.text().equals(earlier.text())
                && (sameAuthor || current.text().length() >= MIN_CHARS_CROSS_AUTHOR_EXACT)) {
            return "exact duplicate of review #" + earlier.review().id();
        }
        if (current.words().size() >= MIN_WORDS_NEAR_DUPLICATE && earlier.words().size() >= MIN_WORDS_NEAR_DUPLICATE) {
            double similarity = TextNormalizer.jaccard(current.words(), earlier.words());
            double threshold = sameAuthor ? SAME_AUTHOR_SIMILARITY : CROSS_AUTHOR_SIMILARITY;
            if (similarity >= threshold) {
                return String.format(Locale.ROOT, "near-duplicate of review #%d (similarity %.2f)",
                        earlier.review().id(), similarity);
            }
        }
        return null;
    }
}
