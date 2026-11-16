package com.reviewlens.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns raw model text into a validated ReviewSummary, or throws InvalidSummaryException with a
 * message specific enough to send back to the model in a repair prompt.
 *
 * Lenient where it is safe (markdown fences, reviewId as "12", extra fields, too many items: trimmed);
 * strict where it matters (valid JSON, known sentiment, required overview).
 */
@Component
public class SummaryParser {

    static final Set<String> SENTIMENTS = Set.of("positive", "mixed", "negative");
    static final int MAX_ITEMS = 5;
    static final int MAX_QUOTES = 3;
    static final int MAX_ITEM_CHARS = 300;
    static final int MAX_OVERVIEW_CHARS = 600;

    private final ObjectMapper json;

    public SummaryParser(ObjectMapper json) {
        this.json = json;
    }

    public ReviewSummary parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidSummaryException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidSummaryException("no JSON object found");
        }
        JsonNode root;
        try {
            root = json.readTree(raw.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new InvalidSummaryException("malformed JSON: " + e.getOriginalMessage());
        }

        String sentiment = root.path("sentiment").asText("").strip().toLowerCase(Locale.ROOT);
        if (!SENTIMENTS.contains(sentiment)) {
            throw new InvalidSummaryException("\"sentiment\" must be one of positive, mixed, negative");
        }
        String overview = root.path("overview").asText("").strip();
        if (overview.isEmpty()) {
            throw new InvalidSummaryException("\"overview\" is required");
        }
        return new ReviewSummary(sentiment, truncate(overview, MAX_OVERVIEW_CHARS),
                strings(root, "pros"), strings(root, "cons"), quotes(root));
    }

    private static List<String> strings(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidSummaryException("\"" + field + "\" must be an array of strings");
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String s = item.isTextual() ? item.asText().strip() : "";
            if (!s.isEmpty() && out.size() < MAX_ITEMS) {
                out.add(truncate(s, MAX_ITEM_CHARS));
            }
        }
        return List.copyOf(out);
    }

    private static List<ReviewSummary.Quote> quotes(JsonNode root) {
        JsonNode node = root.get("quotes");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidSummaryException("\"quotes\" must be an array of {reviewId, text} objects");
        }
        List<ReviewSummary.Quote> out = new ArrayList<>();
        for (JsonNode q : node) {
            if (out.size() >= MAX_QUOTES) {
                break;
            }
            JsonNode id = q.path("reviewId");
            long reviewId;
            if (id.isIntegralNumber()) {
                reviewId = id.asLong();
            } else if (id.isTextual() && id.asText().strip().matches("\\d+")) {
                reviewId = Long.parseLong(id.asText().strip());
            } else {
                throw new InvalidSummaryException("each quote needs a numeric \"reviewId\"");
            }
            String text = q.path("text").asText("").strip();
            if (!text.isEmpty()) {
                out.add(new ReviewSummary.Quote(reviewId, truncate(text, MAX_ITEM_CHARS)));
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
