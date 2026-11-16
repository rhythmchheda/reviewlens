package com.reviewlens.summary;

public record SummaryResponse(long productId,
                              String productName,
                              ReviewStats stats,
                              String summary,
                              int reviewsUsed,
                              int reviewsOmitted,
                              String provider) {
}
