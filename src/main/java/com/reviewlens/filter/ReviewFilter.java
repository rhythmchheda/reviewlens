package com.reviewlens.filter;

import com.reviewlens.model.Review;

import java.util.List;

/**
 * One step of the filter pipeline (Chain of Responsibility). Filters see the whole list, because some
 * decisions (duplicates) depend on other reviews. Every rejection carries a reason so results are auditable.
 */
public interface ReviewFilter {

    String name();

    /** Must preserve input order in {@code kept}. */
    FilterResult apply(List<Review> reviews);
}
