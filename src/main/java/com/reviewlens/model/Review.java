package com.reviewlens.model;

import java.time.Instant;

public record Review(long id, long productId, int rating, String author, String text, Instant createdAt) {
}
