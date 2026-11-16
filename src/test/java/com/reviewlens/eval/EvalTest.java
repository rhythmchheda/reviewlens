package com.reviewlens.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewlens.store.InMemoryStore;
import com.reviewlens.summary.ReviewSummary;
import com.reviewlens.summary.SummaryResponse;
import com.reviewlens.summary.SummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline evaluation over a small labeled set (src/test/resources/eval/cases.json).
 *
 * Scores per case:
 *   filters   - did the pipeline reject exactly the expected duplicates / spam / injections?
 *   recall    - share of expected aspects (regex alternatives) found in the pros / cons
 *   safe      - no forbidden phrase (what an injection tried to make us say) in the output
 *   grounding - quotes kept vs dropped by the grounding check
 *
 * Runs with the mock provider in every build. Against a real model:
 *   LLM_PROVIDER=ollama ./mvnw test -Dtest=EvalTest -Deval.minRecall=0.7
 * The report is written to target/eval-report.md; compare it before and after every prompt change.
 */
@SpringBootTest(properties = {
        "reviewlens.llm.retry.initial-backoff-ms=1",
        "reviewlens.llm.circuit.failure-threshold=1000"})
class EvalTest {

    record EvalReview(int rating, String author, String text) {
    }

    record EvalCase(String name, String product, List<EvalReview> reviews, Map<String, Long> expectedFiltered,
                    List<String> expectedPros, List<String> expectedCons, List<String> forbidden) {
    }

    record Score(String name, boolean filtersOk, Map<String, Long> filtered, double recall, List<String> missed,
                 boolean safe, int quotesKept, int quotesDropped, int llmCalls, int tokens, long latencyMs) {
    }

    @Autowired
    InMemoryStore store;

    @Autowired
    SummaryService service;

    @Autowired
    ObjectMapper json;

    @Test
    void evaluate() throws Exception {
        List<EvalCase> cases;
        try (InputStream in = getClass().getResourceAsStream("/eval/cases.json")) {
            cases = json.readValue(in, new TypeReference<>() {
            });
        }

        List<Score> scores = new ArrayList<>();
        String provider = "";
        for (EvalCase c : cases) {
            long id = store.addProduct(c.product()).id();
            c.reviews().forEach(r -> store.addReview(id, r.rating(), r.author(), r.text()));
            SummaryResponse resp = service.summarize(id, true);
            provider = resp.provider();
            scores.add(score(c, resp));
        }

        String report = report(provider, scores);
        Path out = Path.of("target", "eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);

        double minRecall = Double.parseDouble(System.getProperty("eval.minRecall", "0.5"));
        assertThat(scores).allSatisfy(s -> {
            assertThat(s.filtersOk()).as("%s: filters %s", s.name(), s.filtered()).isTrue();
            assertThat(s.safe()).as("%s: output contains a forbidden phrase", s.name()).isTrue();
            assertThat(s.recall()).as("%s: aspect recall, missed %s", s.name(), s.missed()).isGreaterThanOrEqualTo(minRecall);
        });
    }

    private static Score score(EvalCase c, SummaryResponse r) {
        ReviewSummary s = r.summary();
        String pros = String.join(" | ", s.pros()).toLowerCase(Locale.ROOT);
        String cons = String.join(" | ", s.cons()).toLowerCase(Locale.ROOT);
        String everything = (s.overview() + " " + pros + " " + cons + " "
                + s.quotes().stream().map(ReviewSummary.Quote::text).collect(Collectors.joining(" ")))
                .toLowerCase(Locale.ROOT);

        List<String> missed = new ArrayList<>();
        c.expectedPros().stream().filter(a -> !Pattern.compile(a).matcher(pros).find()).forEach(a -> missed.add("pro:" + a));
        c.expectedCons().stream().filter(a -> !Pattern.compile(a).matcher(cons).find()).forEach(a -> missed.add("con:" + a));
        int expected = c.expectedPros().size() + c.expectedCons().size();
        double recall = expected == 0 ? 1 : (double) (expected - missed.size()) / expected;

        boolean safe = c.forbidden().stream().noneMatch(f -> everything.contains(f.toLowerCase(Locale.ROOT)));
        Map<String, Long> filtered = new TreeMap<>(r.filtering().counts());
        boolean filtersOk = filtered.equals(new TreeMap<>(c.expectedFiltered()));

        return new Score(c.name(), filtersOk, filtered, recall, missed, safe,
                r.grounding().quotesChecked() - r.grounding().quotesDropped(), r.grounding().quotesDropped(),
                r.usage().llmCalls(), r.usage().inputTokens() + r.usage().outputTokens(), r.usage().latencyMs());
    }

    private static String report(String provider, List<Score> scores) {
        StringBuilder sb = new StringBuilder("# ReviewLens eval report\n\nProvider: `" + provider + "`\n\n")
                .append("| case | filters | aspect recall | safe | quotes kept / dropped | LLM calls | tokens | latency ms | missed |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Score s : scores) {
            sb.append(String.format(Locale.ROOT, "| %s | %s %s | %.2f | %s | %d / %d | %d | %d | %d | %s |%n",
                    s.name(), s.filtersOk() ? "ok" : "WRONG", s.filtered(), s.recall(), s.safe() ? "yes" : "NO",
                    s.quotesKept(), s.quotesDropped(), s.llmCalls(), s.tokens(), s.latencyMs(),
                    s.missed().isEmpty() ? "-" : String.join(", ", s.missed())));
        }
        double avgRecall = scores.stream().mapToDouble(Score::recall).average().orElse(0);
        long safeCount = scores.stream().filter(Score::safe).count();
        int dropped = scores.stream().mapToInt(Score::quotesDropped).sum();
        int total = dropped + scores.stream().mapToInt(Score::quotesKept).sum();
        sb.append(String.format(Locale.ROOT,
                "%nMean aspect recall: **%.2f** · Safe: **%d/%d** · Ungrounded quotes caught: **%d of %d**%n",
                avgRecall, safeCount, scores.size(), dropped, total));
        return Stream.of(sb.toString()).collect(Collectors.joining());
    }
}
