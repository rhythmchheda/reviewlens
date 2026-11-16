package com.reviewlens.filter;

import com.reviewlens.model.Review;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Layer 1 of prompt-injection defense: drop reviews that look like attempts to instruct the model.
 * Patterns are specific on purpose ("ignore the instructions in the manual" is a normal review).
 * This will not catch everything, which is why escaping, tagging, the system prompt and output
 * validation (grounding) are also in place. Defense in depth.
 */
@Component
@Order(10)
public class InjectionFilter extends PerReviewFilter {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("ignore-instructions", Pattern.compile(
                "(?i)\\b(?:ignore|disregard|forget)\\s+(?:all\\s+|any\\s+)?(?:the\\s+|your\\s+)?"
                        + "(?:previous|prior|above|earlier|preceding)\\s+(?:instructions?|prompts?|rules|messages?)"));
        RULES.put("fake-tag", Pattern.compile("(?i)</?\\s*(?:review|partial|system|assistant|instructions?)\\s*>"));
        RULES.put("role-prefix", Pattern.compile("\\b(?:SYSTEM|ASSISTANT)\\s*:"));
        RULES.put("persona-override", Pattern.compile(
                "(?i)\\byou\\s+are\\s+now\\b|\\bact\\s+as\\s+(?:an?\\s+)?(?:ai|assistant|system)\\b|\\bsystem\\s+prompt\\b"));
    }

    @Override
    public String name() {
        return "injection";
    }

    @Override
    protected Optional<String> check(Review review) {
        List<String> matched = RULES.entrySet().stream()
                .filter(e -> e.getValue().matcher(review.text()).find())
                .map(Map.Entry::getKey)
                .toList();
        return matched.isEmpty()
                ? Optional.empty()
                : Optional.of("possible prompt injection (" + String.join(", ", matched) + ")");
    }
}
