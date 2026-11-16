package com.reviewlens.summary;

import com.reviewlens.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.reviewlens.TestReviews.review;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryCacheTest {

    private final SummaryCache cache = new SummaryCache();

    private static SummaryResponse response(String provider) {
        return new SummaryResponse(1, "p", null, null, null, null, null, null, provider, false, false);
    }

    @Test
    void keyChangesWhenReviewsChange() {
        var a = review("a", 5, "x");
        var b = review("b", 4, "y");
        assertThat(SummaryCache.key(List.of(a, b))).isEqualTo(SummaryCache.key(List.of(b, a)));
        assertThat(SummaryCache.key(List.of(a))).isNotEqualTo(SummaryCache.key(List.of(a, b)));
    }

    @Test
    void secondLookupIsHit() {
        AtomicInteger loads = new AtomicInteger();
        cache.getOrCompute(1, "k", false, () -> { loads.incrementAndGet(); return response("x"); });
        SummaryCache.Lookup second = cache.getOrCompute(1, "k", false, () -> { loads.incrementAndGet(); return response("x"); });

        assertThat(second.hit()).isTrue();
        assertThat(loads).hasValue(1);
    }

    @Test
    void concurrentRequestsShareOneComputation() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SummaryCache.Lookup>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.getOrCompute(1, "k", false, () -> {
                        loads.incrementAndGet();
                        sleep(200); // a slow LLM call
                        return response("x");
                    });
                }));
            }
            start.countDown();
            for (Future<SummaryCache.Lookup> f : results) {
                assertThat(f.get().response().provider()).isEqualTo("x");
            }
        }
        assertThat(loads).hasValue(1); // one LLM bill, not ten
    }

    @Test
    void failuresAreNotCachedButLastGoodIsKept() {
        cache.getOrCompute(1, "k1", false, () -> response("good"));

        assertThatThrownBy(() -> cache.getOrCompute(1, "k2", false, () -> { throw new LlmException("down"); }))
                .isInstanceOf(LlmException.class);
        assertThat(cache.lastGood(1)).get().extracting(SummaryResponse::provider).isEqualTo("good");

        SummaryCache.Lookup retry = cache.getOrCompute(1, "k2", false, () -> response("recovered"));
        assertThat(retry.hit()).isFalse();
        assertThat(retry.response().provider()).isEqualTo("recovered");
    }

    @Test
    void refreshForcesRecompute() {
        cache.getOrCompute(1, "k", false, () -> response("old"));
        SummaryCache.Lookup l = cache.getOrCompute(1, "k", true, () -> response("new"));
        assertThat(l.hit()).isFalse();
        assertThat(l.response().provider()).isEqualTo("new");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
