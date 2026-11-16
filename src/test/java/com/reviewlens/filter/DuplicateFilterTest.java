package com.reviewlens.filter;

import com.reviewlens.model.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.reviewlens.TestReviews.review;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateFilterTest {

    private final DuplicateFilter filter = new DuplicateFilter();

    @Test
    void dropsExactRepeatFromSameAuthorAndKeepsEarliest() {
        Review first = review("asha", 5, "Great sound quality. Very happy.");
        Review repeat = review("asha", 5, "great sound quality, very happy!!");

        FilterResult r = filter.apply(List.of(repeat, first)); // order of input does not matter

        assertThat(r.kept()).containsExactly(first);
        assertThat(r.rejected()).singleElement()
                .satisfies(x -> assertThat(x.detail()).isEqualTo("exact duplicate of review #" + first.id()));
    }

    @Test
    void keepsShortIdenticalReviewsFromDifferentPeople() {
        Review a = review("ana", 5, "Great product!");
        Review b = review("bob", 5, "Great product!");

        assertThat(filter.apply(List.of(a, b)).kept()).containsExactly(a, b);
    }

    @Test
    void dropsLongIdenticalReviewsFromDifferentAccounts() {
        String text = "Works exactly as described and arrived two days earlier than promised.";
        Review a = review("ana", 5, text);
        Review b = review("bot123", 5, text);

        assertThat(filter.apply(List.of(a, b)).kept()).containsExactly(a);
    }

    @Test
    void dropsNearDuplicateFromSameAuthor() {
        Review a = review("asha", 5, "Great sound quality and the battery lasts about 8 hours. Very happy.");
        Review b = review("asha", 5, "great sound quality and battery lasts 8 hours, very happy!!");

        FilterResult r = filter.apply(List.of(a, b));

        assertThat(r.kept()).containsExactly(a);
        assertThat(r.rejected().get(0).detail()).startsWith("near-duplicate of review #" + a.id());
    }

    @Test
    void keepsDifferentReviews() {
        Review a = review("ana", 4, "Battery is great but the strap is uncomfortable after a few hours.");
        Review b = review("ana", 2, "Stopped syncing after a week and support never replied to my emails.");

        assertThat(filter.apply(List.of(a, b)).kept()).containsExactly(a, b);
    }
}
