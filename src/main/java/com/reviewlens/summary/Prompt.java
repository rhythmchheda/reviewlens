package com.reviewlens.summary;

public record Prompt(String system, String user, int reviewsIncluded, int reviewsOmitted) {
}
