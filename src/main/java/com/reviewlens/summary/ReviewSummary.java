package com.reviewlens.summary;

import java.util.List;

/** The structured output contract the LLM must follow (validated by SummaryParser). */
public record ReviewSummary(String sentiment,
                            String overview,
                            List<String> pros,
                            List<String> cons,
                            List<Quote> quotes) {

    public record Quote(long reviewId, String text) {
    }

    public ReviewSummary withQuotes(List<Quote> newQuotes) {
        return new ReviewSummary(sentiment, overview, pros, cons, List.copyOf(newQuotes));
    }
}
