package com.reviewlens.summary;

import com.reviewlens.model.Review;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Numbers are computed in code, never by the LLM, so they are always correct. */
public record ReviewStats(int count, double averageRating, Map<Integer, Long> ratingDistribution) {

    public static ReviewStats of(List<Review> reviews) {
        Map<Integer, Long> dist = new TreeMap<>();
        for (int star = 1; star <= 5; star++) {
            dist.put(star, 0L);
        }
        reviews.forEach(r -> dist.merge(r.rating(), 1L, Long::sum));
        double avg = reviews.stream().mapToInt(Review::rating).average().orElse(0);
        return new ReviewStats(reviews.size(), Math.round(avg * 100) / 100.0, dist);
    }
}
