package com.reviewlens.summary;

import com.reviewlens.llm.LlmException;
import com.reviewlens.model.Review;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-product summary cache with three properties worth explaining:
 *
 *  1. Content-addressed key: hash(prompt version + review ids). A new review changes the key, so
 *     invalidation is automatic; no "delete on write" code path to forget.
 *  2. Single-flight: concurrent requests for the same key share one in-flight computation
 *     (one LLM bill, not N). Implemented by caching the CompletableFuture, not the value.
 *  3. Last-good copy: if a fresh computation fails, the service can serve the previous summary
 *     marked stale instead of an error.
 *
 * In-memory and one entry per product. Multi-instance deployment would move this to Redis.
 */
@Component
public class SummaryCache {

    public record Lookup(SummaryResponse response, boolean hit) {
    }

    private record Entry(String key, CompletableFuture<SummaryResponse> future) {
    }

    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SummaryResponse> lastGood = new ConcurrentHashMap<>();

    public static String key(List<Review> reviews) {
        StringBuilder sb = new StringBuilder(PromptBuilder.PROMPT_VERSION);
        reviews.stream().mapToLong(Review::id).sorted().forEach(id -> sb.append('|').append(id));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Lookup getOrCompute(long productId, String key, boolean refresh, Supplier<SummaryResponse> loader) {
        Entry mine = new Entry(key, new CompletableFuture<>());
        Entry winner = entries.compute(productId, (id, old) ->
                !refresh && old != null && old.key().equals(key) && !old.future().isCompletedExceptionally()
                        ? old
                        : mine);

        if (winner != mine) {
            try {
                return new Lookup(winner.future().join(), true);
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new LlmException("summary failed", e.getCause());
            }
        }

        try {
            SummaryResponse response = loader.get();
            mine.future().complete(response);
            lastGood.put(productId, response);
            return new Lookup(response, false);
        } catch (RuntimeException e) {
            mine.future().completeExceptionally(e);
            entries.remove(productId, mine); // never cache failures
            throw e;
        }
    }

    public Optional<SummaryResponse> lastGood(long productId) {
        return Optional.ofNullable(lastGood.get(productId));
    }
}
