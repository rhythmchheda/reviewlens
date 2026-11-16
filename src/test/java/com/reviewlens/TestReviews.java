package com.reviewlens;

import com.reviewlens.model.Review;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Test helper: reviews with increasing ids and timestamps (so "earliest" is well defined). */
public final class TestReviews {

    private static final AtomicLong IDS = new AtomicLong(1000);
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private TestReviews() {
    }

    public static Review review(String author, int rating, String text) {
        long id = IDS.incrementAndGet();
        return new Review(id, 1, rating, author, text, BASE.plusSeconds(id));
    }
}
