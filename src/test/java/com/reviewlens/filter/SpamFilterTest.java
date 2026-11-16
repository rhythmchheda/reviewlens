package com.reviewlens.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.reviewlens.TestReviews.review;
import static org.assertj.core.api.Assertions.assertThat;

class SpamFilterTest {

    private final SpamFilter filter = new SpamFilter();

    @Test
    void flagsPromotionalReviewWithReasons() {
        FilterResult r = filter.apply(List.of(review("promo99", 5,
                "BEST DEAL!!! Buy cheap earbuds at spam-deals.example now!!! Use code WIN")));

        assertThat(r.kept()).isEmpty();
        assertThat(r.rejected().get(0).detail()).contains("link", "promo code", "!!!");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "This was a good deal for the price.",              // one sales word is not spam
            "LOVE IT",                                           // short caps is enthusiasm, not spam
            "I had to use code that came in the box to pair it.", // lower-case 'code' is not a promo code
            "Battery lasts 8.5 hours, which is great!",          // a decimal is not a domain
    })
    void keepsGenuineReviews(String text) {
        assertThat(filter.apply(List.of(review("real", 4, text))).kept()).hasSize(1);
    }

    @Test
    void flagsShoutingWithLink() {
        FilterResult r = filter.apply(List.of(review("x", 5, "VISIT WWW.CHEAPWATCHES.SHOP FOR AMAZING PRICES")));
        assertThat(r.kept()).isEmpty();
    }
}
