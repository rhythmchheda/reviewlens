package com.reviewlens.filter;

import com.reviewlens.model.Review;
import com.reviewlens.text.TextNormalizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based spam scoring: several weak signals add up, and a review is spam only at score >= 3.
 * One signal alone (e.g. the word "deal") never rejects a review, which keeps false positives low.
 *
 * Next step if rules are not enough: send only borderline scores (1-2) to an LLM classifier (cost-aware cascade).
 */
@Component
@Order(20)
public class SpamFilter extends PerReviewFilter {

    static final int THRESHOLD = 3;

    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:com|net|org|io|shop|store|xyz|info|biz|example|ly)\\b");
    // "use code" is case-insensitive, the code itself must be upper case: avoids "I had to use code that..."
    private static final Pattern PROMO_CODE = Pattern.compile(
            "(?i:\\b(?:use|promo|coupon|discount)\\s+code)\\s*:?\\s*[A-Z0-9]{3,}\\b");
    private static final Pattern EXCLAMATIONS = Pattern.compile("!{3,}");
    private static final Set<String> KEYWORDS = Set.of(
            "buy", "cheap", "cheapest", "discount", "deal", "deals", "free", "click", "visit", "promo",
            "coupon", "offer", "giveaway", "whatsapp", "telegram", "dm", "subscribe", "earn");

    @Override
    public String name() {
        return "spam";
    }

    @Override
    protected Optional<String> check(Review review) {
        String text = review.text();
        int score = 0;
        List<String> signals = new ArrayList<>();

        if (LINK.matcher(text).find()) {
            score += 2;
            signals.add("link");
        }
        if (PROMO_CODE.matcher(text).find()) {
            score += 2;
            signals.add("promo code");
        }
        if (isShouting(text)) {
            score += 1;
            signals.add("all caps");
        }
        if (EXCLAMATIONS.matcher(text).find()) {
            score += 1;
            signals.add("!!!");
        }
        long keywords = TextNormalizer.words(text).stream().filter(KEYWORDS::contains).count();
        if (keywords >= 2) {
            score += keywords >= 4 ? 2 : 1;
            signals.add("sales words x" + keywords);
        }

        return score >= THRESHOLD
                ? Optional.of("spam score " + score + " (" + String.join(", ", signals) + ")")
                : Optional.empty();
    }

    private static boolean isShouting(String text) {
        long letters = text.chars().filter(Character::isLetter).count();
        long upper = text.chars().filter(Character::isUpperCase).count();
        return letters >= 12 && (double) upper / letters > 0.6;
    }
}
