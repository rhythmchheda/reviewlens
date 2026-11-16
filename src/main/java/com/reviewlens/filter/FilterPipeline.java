package com.reviewlens.filter;

import com.reviewlens.model.Review;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every ReviewFilter bean in @Order: injection (10), spam (20), duplicate (30).
 * Per-review filters run first, so a spam review that is also duplicated is counted as spam.
 * Adding a filter = adding a bean; nothing here changes (open/closed principle).
 */
@Component
public class FilterPipeline {

    private final List<ReviewFilter> filters;

    public FilterPipeline(List<ReviewFilter> filters) {
        this.filters = List.copyOf(filters);
    }

    public FilterResult apply(List<Review> reviews) {
        List<Review> current = reviews;
        List<RejectedReview> rejected = new ArrayList<>();
        for (ReviewFilter filter : filters) {
            FilterResult step = filter.apply(current);
            current = step.kept();
            rejected.addAll(step.rejected());
        }
        return new FilterResult(current, rejected);
    }

    public List<String> filterNames() {
        return filters.stream().map(ReviewFilter::name).toList();
    }
}
