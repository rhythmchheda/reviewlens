package com.reviewlens.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryParserTest {

    private final SummaryParser parser = new SummaryParser(new ObjectMapper());

    @Test
    void parsesValidJson() {
        ReviewSummary s = parser.parse("""
                {"sentiment":"Mixed","overview":"Good sound, poor fit.","pros":["sound"],"cons":["fit"],
                 "quotes":[{"reviewId":7,"text":"great sound"}]}""");

        assertThat(s.sentiment()).isEqualTo("mixed");
        assertThat(s.pros()).containsExactly("sound");
        assertThat(s.quotes()).containsExactly(new ReviewSummary.Quote(7, "great sound"));
    }

    @Test
    void toleratesMarkdownFencesAndStringIds() {
        ReviewSummary s = parser.parse("""
                Here you go:
                ```json
                {"sentiment":"positive","overview":"Liked.","pros":[],"cons":[],"quotes":[{"reviewId":"12","text":"nice"}]}
                ```""");
        assertThat(s.quotes().get(0).reviewId()).isEqualTo(12);
    }

    @Test
    void capsListSizes() {
        ReviewSummary s = parser.parse("""
                {"sentiment":"positive","overview":"x","pros":["a","b","c","d","e","f","g"],"cons":[],"quotes":[]}""");
        assertThat(s.pros()).hasSize(SummaryParser.MAX_ITEMS);
    }

    @Test
    void rejectsUnknownSentiment() {
        assertThatThrownBy(() -> parser.parse("{\"sentiment\":\"great\",\"overview\":\"x\"}"))
                .isInstanceOf(InvalidSummaryException.class)
                .hasMessageContaining("sentiment");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{\"sentiment\": \"positive\", \"overview\": }"))
                .isInstanceOf(InvalidSummaryException.class)
                .hasMessageStartingWith("malformed JSON");
    }

    @Test
    void rejectsProse() {
        assertThatThrownBy(() -> parser.parse("Customers love this product."))
                .isInstanceOf(InvalidSummaryException.class)
                .hasMessage("no JSON object found");
    }
}
