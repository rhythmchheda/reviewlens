package com.reviewlens.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewlens.summary.TokenBudget;
import com.reviewlens.text.TextNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline stand-in for an LLM (default provider). It answers the same JSON contract
 * as a real model, using simple heuristics, so the whole pipeline runs and is testable with no API key.
 *
 * It also simulates a hallucination: it adds one quote that no review contains, so the grounding
 * check has something real to catch (turn off with reviewlens.llm.mock.hallucinate=false).
 */
@Component
@ConditionalOnProperty(name = "reviewlens.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmProvider implements LlmProvider {

    public static final String FABRICATED_QUOTE = "Honestly the best purchase I have ever made";

    private static final Pattern REVIEW = Pattern.compile(
            "<review id=\"(\\d+)\" rating=\"(\\d)\">(.*?)</review>", Pattern.DOTALL);
    private static final Pattern PARTIAL = Pattern.compile("<partial>(.*?)</partial>", Pattern.DOTALL);
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]");

    private final ObjectMapper json;
    private final boolean hallucinate;

    public MockLlmProvider(ObjectMapper json, @Value("${reviewlens.llm.mock.hallucinate:true}") boolean hallucinate) {
        this.json = json;
        this.hallucinate = hallucinate;
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        try {
            String out = userPrompt.contains("<partial>") ? reduce(userPrompt) : map(userPrompt);
            return new LlmResponse(out, TokenBudget.estimate(systemPrompt + userPrompt), TokenBudget.estimate(out));
        } catch (JsonProcessingException e) {
            throw new LlmException("mock provider failed", e);
        }
    }

    @Override
    public String name() {
        return "mock";
    }

    private String map(String userPrompt) throws JsonProcessingException {
        Set<String> pros = new LinkedHashSet<>();
        Set<String> cons = new LinkedHashSet<>();
        List<Map<String, Object>> quotes = new ArrayList<>();
        int ratingSum = 0;
        int count = 0;
        long firstId = -1;

        Matcher m = REVIEW.matcher(userPrompt);
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            int rating = Integer.parseInt(m.group(2));
            String text = TextNormalizer.unescapeHtml(m.group(3));
            ratingSum += rating;
            count++;
            if (firstId < 0) {
                firstId = id;
            }

            String sentence = firstSentence(text);
            int but = sentence.toLowerCase(Locale.ROOT).indexOf(" but ");
            if (but >= 0) {
                if (rating >= 3) {
                    addIfPresent(pros, sentence.substring(0, but));
                }
                addIfPresent(cons, sentence.substring(but + 5));
            } else if (rating >= 4) {
                addIfPresent(pros, sentence);
            } else if (rating <= 2) {
                addIfPresent(cons, sentence);
            }
            if (quotes.size() < 2) {
                quotes.add(quote(id, firstWords(text, 6)));
            }
        }
        if (hallucinate && count > 0) {
            quotes.add(quote(firstId, FABRICATED_QUOTE));
        }

        String sentiment = count == 0 ? "mixed" : sentimentFor((double) ratingSum / count);
        return toJson(sentiment, overview(count + " reviews", sentiment, pros, cons), pros, cons, quotes);
    }

    private String reduce(String userPrompt) throws JsonProcessingException {
        Set<String> pros = new LinkedHashSet<>();
        Set<String> cons = new LinkedHashSet<>();
        Set<String> sentiments = new LinkedHashSet<>();
        List<Map<String, Object>> quotes = new ArrayList<>();
        int partials = 0;

        Matcher m = PARTIAL.matcher(userPrompt);
        while (m.find()) {
            JsonNode node = json.readTree(m.group(1));
            partials++;
            sentiments.add(node.path("sentiment").asText("mixed"));
            node.path("pros").forEach(p -> pros.add(p.asText()));
            node.path("cons").forEach(c -> cons.add(c.asText()));
            for (JsonNode q : node.path("quotes")) {
                if (quotes.size() < 3) {
                    quotes.add(quote(q.path("reviewId").asLong(), q.path("text").asText()));
                }
            }
        }
        String sentiment = sentiments.size() == 1 ? sentiments.iterator().next() : "mixed";
        return toJson(sentiment, overview(partials + " partial summaries", sentiment, pros, cons), pros, cons, quotes);
    }

    private String toJson(String sentiment, String overview, Set<String> pros, Set<String> cons,
                          List<Map<String, Object>> quotes) throws JsonProcessingException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sentiment", sentiment);
        out.put("overview", overview);
        out.put("pros", pros.stream().limit(5).toList());
        out.put("cons", cons.stream().limit(5).toList());
        out.put("quotes", quotes);
        return json.writeValueAsString(out);
    }

    private static String overview(String basis, String sentiment, Set<String> pros, Set<String> cons) {
        String liked = pros.isEmpty() ? "nothing specific" : lowerFirst(pros.iterator().next());
        String disliked = cons.isEmpty() ? "none reported" : lowerFirst(cons.iterator().next());
        return "Based on " + basis + ", sentiment is " + sentiment + ". Most praised: " + liked
                + ". Main complaint: " + disliked + ".";
    }

    private static Map<String, Object> quote(long id, String text) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("reviewId", id);
        q.put("text", text);
        return q;
    }

    private static String sentimentFor(double avg) {
        return avg >= 4 ? "positive" : avg <= 2.5 ? "negative" : "mixed";
    }

    private static String firstSentence(String text) {
        Matcher end = SENTENCE_END.matcher(text);
        return end.find() ? text.substring(0, end.start()) : text;
    }

    private static String firstWords(String text, int n) {
        String[] words = text.strip().split("\\s+");
        return String.join(" ", Arrays.copyOf(words, Math.min(n, words.length)));
    }

    private static void addIfPresent(Set<String> target, String phrase) {
        String p = phrase.strip().replaceAll("[,;:]+$", "").strip();
        if (!p.isEmpty()) {
            target.add(Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
