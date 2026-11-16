package com.reviewlens.summary;

import com.reviewlens.llm.CircuitBreaker;
import com.reviewlens.llm.ResilientLlm;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metrics, visible at /actuator/metrics/{name}. These are the numbers you would put on a
 * dashboard and alert on: cost, latency, cache hit rate, grounding failures, filter volume.
 */
@Component
public class SummaryMetrics {

    private final MeterRegistry registry;

    public SummaryMetrics(MeterRegistry registry, ResilientLlm llm) {
        this.registry = registry;
        Gauge.builder("reviewlens.llm.circuit.open", llm, l -> l.circuitState() == CircuitBreaker.State.OPEN ? 1 : 0)
                .description("1 when the LLM circuit breaker is open")
                .register(registry);
    }

    /** Called once per freshly computed summary (not for cache hits). */
    public void recordComputed(SummaryResponse r) {
        Usage u = r.usage();
        registry.counter("reviewlens.llm.calls").increment(u.llmCalls());
        registry.counter("reviewlens.llm.repairs").increment(u.repairs());
        registry.counter("reviewlens.llm.tokens", "type", "input").increment(u.inputTokens());
        registry.counter("reviewlens.llm.tokens", "type", "output").increment(u.outputTokens());
        registry.counter("reviewlens.llm.cost.usd").increment(u.estimatedCostUsd());
        registry.timer("reviewlens.summary.latency").record(Duration.ofMillis(u.latencyMs()));

        SummaryResponse.Grounding g = r.grounding();
        registry.counter("reviewlens.grounding.quotes", "result", "kept").increment(g.quotesChecked() - g.quotesDropped());
        registry.counter("reviewlens.grounding.quotes", "result", "dropped").increment(g.quotesDropped());

        r.filtering().counts().forEach((filter, n) ->
                registry.counter("reviewlens.filter.rejected", "filter", filter).increment(n));
    }

    /** result = hit | miss | stale */
    public void cache(String result) {
        registry.counter("reviewlens.summary.cache", "result", result).increment();
    }

    public void failure() {
        registry.counter("reviewlens.summary.failures").increment();
    }
}
