package com.reviewlens.summary;

import com.reviewlens.model.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.reviewlens.TestReviews.review;
import static org.assertj.core.api.Assertions.assertThat;

class GroundingCheckerTest {

    private final GroundingChecker checker = new GroundingChecker();
    private final Review battery = review("a", 5, "Great sound quality and the battery lasts about 8 hours.");

    private GroundingChecker.Result check(ReviewSummary.Quote... quotes) {
        ReviewSummary s = new ReviewSummary("positive", "x", List.of(), List.of(), List.of(quotes));
        return checker.check(s, List.of(battery));
    }

    @Test
    void keepsExactQuoteIgnoringCaseAndPunctuation() {
        var r = check(new ReviewSummary.Quote(battery.id(), "the Battery lasts about 8 hours!"));
        assertThat(r.summary().quotes()).hasSize(1);
        assertThat(r.dropped()).isEmpty();
    }

    @Test
    void dropsFabricatedQuote() {
        var r = check(new ReviewSummary.Quote(battery.id(), "best purchase ever"));
        assertThat(r.summary().quotes()).isEmpty();
        assertThat(r.dropped().get(0).reason()).isEqualTo("text not found in the cited review");
    }

    @Test
    void dropsQuoteCitingWrongReview() {
        var r = check(new ReviewSummary.Quote(999_999, "Great sound quality"));
        assertThat(r.dropped().get(0).reason()).isEqualTo("cites a review that is not in the input");
    }

    @Test
    void matchesWholeWordsOnly() {
        var r = check(new ReviewSummary.Quote(battery.id(), "the batter"));
        assertThat(r.summary().quotes()).isEmpty();
    }

    @Test
    void reportsCounts() {
        var r = check(new ReviewSummary.Quote(battery.id(), "Great sound quality"),
                new ReviewSummary.Quote(battery.id(), "made up"));
        assertThat(r.quotesChecked()).isEqualTo(2);
        assertThat(r.dropped()).hasSize(1);
    }
}
