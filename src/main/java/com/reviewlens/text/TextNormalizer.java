package com.reviewlens.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared text normalization so dedupe, spam rules and grounding all compare text the same way. */
public final class TextNormalizer {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TextNormalizer() {
    }

    /** Unicode-normalize, lowercase, replace punctuation with spaces, collapse whitespace. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(n).replaceAll(" ").strip();
    }

    /** Distinct normalized words. */
    public static Set<String> words(String text) {
        String n = normalize(text);
        return n.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(n.split(" ")));
    }

    /** |A ∩ B| / |A ∪ B|, 0 when both are empty. */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /** Reverses the escaping PromptBuilder applies, so quotes copied from the prompt match the original text. */
    public static String unescapeHtml(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
