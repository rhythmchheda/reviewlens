package com.reviewlens.filter;

public record RejectedReview(long reviewId, String author, String filter, String detail) {
}
