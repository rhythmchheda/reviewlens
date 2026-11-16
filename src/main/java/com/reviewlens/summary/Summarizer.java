package com.reviewlens.summary;

import com.reviewlens.llm.LlmException;
import com.reviewlens.llm.LlmResponse;
import com.reviewlens.llm.ResilientLlm;
import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Map-reduce summarization:
 *   map    - each chunk of reviews -> partial JSON summary (calls run in parallel on virtual threads)
 *   reduce - merge partials in groups until one summary remains
 *   check  - drop quotes that are not grounded in a real review
 *
 * A global semaphore caps concurrent LLM calls across all requests, protecting provider rate limits.
 * Each call gets one repair attempt if the model returns invalid JSON. Transport failures are
 * retried separately, inside ResilientLlm.
 */
@Component
public class Summarizer {

    public record Result(ReviewSummary summary, GroundingChecker.Result grounding, Usage usage) {
    }

    private final ResilientLlm llm;
    private final PromptBuilder prompts;
    private final SummaryParser parser;
    private final GroundingChecker grounding;
    private final Semaphore permits;
    private final double inputPricePerMillion;
    private final double outputPricePerMillion;

    public Summarizer(ResilientLlm llm, PromptBuilder prompts, SummaryParser parser, GroundingChecker grounding,
                      @Value("${reviewlens.llm.max-concurrency}") int maxConcurrency,
                      @Value("${reviewlens.llm.price.input-per-million}") double inputPricePerMillion,
                      @Value("${reviewlens.llm.price.output-per-million}") double outputPricePerMillion) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.grounding = grounding;
        this.permits = new Semaphore(maxConcurrency);
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public Result summarize(Product product, List<Review> reviews) {
        long start = System.nanoTime();
        UsageTracker usage = new UsageTracker();

        List<List<Review>> chunks = prompts.chunk(reviews);
        List<ReviewSummary> partials = runAll(chunks.stream()
                .<Callable<ReviewSummary>>map(chunk -> () -> call(prompts.mapPrompt(product, chunk), usage))
                .toList());

        while (partials.size() > 1) {
            partials = runAll(prompts.groupPartials(partials).stream()
                    .<Callable<ReviewSummary>>map(group -> () -> call(prompts.reducePrompt(product, group), usage))
                    .toList());
        }

        GroundingChecker.Result checked = grounding.check(partials.get(0), reviews);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return new Result(checked.summary(), checked,
                usage.snapshot(chunks.size(), latencyMs, inputPricePerMillion, outputPricePerMillion));
    }

    public String providerName() {
        return llm.name();
    }

    /** One LLM call with validation and a single repair retry. */
    private ReviewSummary call(Prompt prompt, UsageTracker usage) {
        LlmResponse first = complete(prompt, usage);
        try {
            return parser.parse(first.text());
        } catch (InvalidSummaryException e) {
            usage.repair();
            LlmResponse second = complete(prompts.repairPrompt(prompt, first.text(), e.getMessage()), usage);
            return parser.parse(second.text()); // still invalid -> InvalidSummaryException -> HTTP 502
        }
    }

    private LlmResponse complete(Prompt prompt, UsageTracker usage) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted while waiting for an LLM slot", e);
        }
        try {
            LlmResponse response = llm.complete(prompt.system(), prompt.user());
            usage.record(response);
            return response;
        } finally {
            permits.release();
        }
    }

    private static <T> List<T> runAll(List<Callable<T>> tasks) {
        if (tasks.size() == 1) {
            try {
                return List.of(tasks.get(0).call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmException("summary task failed", e);
            }
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
            List<T> results = new ArrayList<>();
            try {
                for (Future<T> f : futures) {
                    results.add(f.get());
                }
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new LlmException("summary task failed", e.getCause());
            } catch (InterruptedException e) {
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted", e);
            }
            return results;
        }
    }
}
