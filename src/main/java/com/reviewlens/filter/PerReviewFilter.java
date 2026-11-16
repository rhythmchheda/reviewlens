package com.reviewlens.filter;

import com.reviewlens.model.Review;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Template Method base for filters that judge each review on its own (spam, injection). */
public abstract class PerReviewFilter implements ReviewFilter {

    /** @return a rejection reason, or empty to keep the review */
    protected abstract Optional<String> check(Review review);

    @Override
    public final FilterResult apply(List<Review> reviews) {
        List<Review> kept = new ArrayList<>();
        List<RejectedReview> rejected = new ArrayList<>();
        for (Review r : reviews) {
            Optional<String> reason = check(r);
            if (reason.isPresent()) {
                rejected.add(new RejectedReview(r.id(), r.author(), name(), reason.get()));
            } else {
                kept.add(r);
            }
        }
        return new FilterResult(kept, rejected);
    }
}
