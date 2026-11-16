# ReviewLens walkthrough: what was built and why

Read this with the code open. It follows one request from start to finish, then explains each design decision and the alternative that was rejected. Each section ends with a **check yourself** question. If you can't answer it without looking, reread that file.

Suggested reading order (about 2 hours):

1. `ProductController` → `SummaryService` (the whole flow in ~60 lines)
2. `filter/` (InjectionFilter, SpamFilter, DuplicateFilter, FilterPipeline)
3. `summary/PromptBuilder` → `Summarizer` → `SummaryParser` → `GroundingChecker`
4. `llm/` (LlmProvider, MockLlmProvider, ResilientLlm, CircuitBreaker)
5. `summary/SummaryCache`, `SummaryMetrics`
6. Tests, especially `ProductApiTest` and `EvalTest`

Git history follows the same path: `git log --oneline` shows Milestone 1 → 2 → 3, and `git show <hash>` shows what each one added.

---

## 1. The request lifecycle

`GET /products/1/summary`

1. **ProductController** passes the request on to `SummaryService.summarize(id, refresh)`.
2. **SummaryService** loads the product and all its reviews, then asks **SummaryCache** for a summary under key `hash("v2" + sorted review ids)`.
3. On a cache miss, `compute()` runs:
   1. **FilterPipeline** removes injection attempts, spam and duplicates, and records why for each one.
   2. **ReviewStats** computes count, average and distribution twice: over kept reviews (`stats`) and over all reviews (`rawStats`).
   3. **Summarizer** runs map-reduce:
      - `PromptBuilder.chunk()` splits reviews to fit the token budget (max 10 per chunk).
      - Each chunk gets a *map* prompt, and the calls run in parallel on virtual threads.
      - Each reply goes through `SummaryParser`. If it's invalid, it is sent back once as a *repair* prompt.
      - If there are several partial summaries, *reduce* prompts merge them until one remains.
      - `GroundingChecker` drops any quote that doesn't appear in the review it cites.
   4. `SummaryMetrics` records tokens, cost, latency, grounding and filter counts.
4. If anything in the LLM path throws `LlmException`, SummaryService returns the **last good** summary with `stale: true`. If there isn't one, `ApiExceptionHandler` returns 502.

**Check yourself:** Why are stats computed in `SummaryService`, not by the LLM? Why are there two sets of stats?

---

## 2. Filters (`filter/`)

**Pattern:** Chain of Responsibility. Each `ReviewFilter` takes a list and returns `FilterResult(kept, rejected)`. Spring injects all filter beans sorted by `@Order`, so adding a filter means adding a class; `FilterPipeline` never changes (open/closed principle).

`PerReviewFilter` is a Template Method base class for filters that judge each review on its own. `DuplicateFilter` implements the interface directly because it needs to compare reviews against each other.

**Order matters:** injection (10) → spam (20) → duplicate (30). The cheap per-review checks run first, so a spam review posted twice is counted as spam, not as a duplicate.

### InjectionFilter
Regex rules for "ignore previous instructions", fake `</review>`/`<system>` tags, `SYSTEM:` prefixes, and persona overrides ("you are now"). The patterns are deliberately narrow: "Ignore the instructions in the manual" is a normal review and is kept (see `InjectionFilterTest`).

It's only the **first** layer. The others:
- `PromptBuilder.sanitize` escapes `<` `>` `&`, so a review can't close its own `<review>` tag. This is the strongest layer, because it doesn't depend on recognizing the attack.
- The system prompt says tagged content is data, not instructions.
- `GroundingChecker` validates output, so even if an attack gets through, made-up quotes don't.

### SpamFilter
A score built from weak signals: link (+2), promo code (+2), all caps (+1), `!!!` (+1), and 2+ sales words (+1, or +2 for 4+). A review is spam only when the score reaches 3 or more. One signal alone never rejects a review. That is a **precision** choice: hiding a real negative review hurts trust more than letting one spam review through.

### DuplicateFilter
- **Exact:** compare normalized text (lowercase, no punctuation, collapsed whitespace). Two different authors only count as duplicates if the text is 40+ characters, because two people can both honestly write "Great product!".
- **Near:** Jaccard similarity on word sets, for reviews with 8+ distinct words. The threshold is 0.8 for the same author and 0.9 across authors.
- The earliest copy is kept.
- It's O(n²) per product, which is fine here. At scale you'd use MinHash + LSH to find candidate pairs without comparing every pair.

**Check yourself:** A review says "Great deal, free shipping!" What's its spam score, and is it kept? (Answer: two sales words = 1 point, so it's kept.)

---

## 3. Prompts and output (`summary/`)

### PromptBuilder
- `MAP_SYSTEM` and `REDUCE_SYSTEM` both end with the JSON `SCHEMA`. One contract, used everywhere.
- **Chunking:** add reviews until the next one would exceed the token budget or the chunk reaches 10 reviews. Long reviews are truncated to 1000 characters, so a single review always fits.
- **Reduce input** is the partial summaries re-serialized as JSON, with `<` and `>` written as `<` and `>`. The JSON stays valid, and LLM output can't close a `<partial>` tag. Output from step one is untrusted input to step two.
- `groupPartials` makes every group hold 2 or more items, so each reduce round shrinks the list and the loop always ends.
- `PROMPT_VERSION` is part of the cache key. Changing a prompt makes old cached summaries invalid.

### TokenBudget
Estimates tokens as characters ÷ 4. It's cheap and good enough for budgeting. When the provider reports real token counts (OpenAI `usage`, Ollama `eval_count`), those are used for cost.

### SummaryParser
- **Lenient where it's safe:** strips markdown fences and prose around the JSON, accepts `"reviewId": "12"`, trims lists that are too long.
- **Strict where it matters:** the JSON must be valid, sentiment must be positive, mixed or negative, and an overview is required.
- The exception message is specific ("sentiment must be one of ..."), because it goes back to the model in the repair prompt.

### Repair loop (in `Summarizer.call`)
Invalid output gets one repair call that includes the exact error. If it fails twice, the result is `InvalidSummaryException` → 502. It stops at one repair because a model that fails twice usually keeps failing, and each extra try costs money and time.

### GroundingChecker
A quote is kept only if, after normalizing both texts, it appears as **whole words** inside the review it cites (so "the batter" doesn't match "the battery"). Quotes citing a filtered-out review are dropped too. The mock deliberately adds one made-up quote so this check has something to catch.

**Limitation to admit:** pros and cons are paraphrases, so they aren't checked. The next step would be an LLM-as-judge or entailment check: "is this claim supported by review X?"

**Check yourself:** Why escape `&` as well as `<` and `>`? (Otherwise text like `&lt;/review&gt;` written by an attacker would be ambiguous after unescaping.)

---

## 4. LLM layer (`llm/`)

### LlmProvider (Strategy)
`complete(system, user) → LlmResponse(text, inputTokens, outputTokens)`. Exactly one implementation is active, chosen by `@ConditionalOnProperty` on `reviewlens.llm.provider`. The service code never names a vendor.

- **OpenAI:** `response_format: json_object`, temperature 0.2.
- **Ollama:** `format: "json"`.
- Both use HTTP timeouts (`HttpClients.withTimeouts`). An LLM call with no timeout can hold a thread forever.

### MockLlmProvider
A deterministic heuristic, not a fake that returns a fixed string. It parses the `<review>` tags, takes the first sentence, and splits on "but" into a pro and a con. It honors the same JSON contract and handles reduce prompts too. This lets the whole pipeline, including map-reduce and grounding, run in tests with no network.

### LlmException.from: retryable or not
Retry on timeouts, connection errors, 5xx and 429. Don't retry other 4xx (for example a bad API key), because the same request will fail the same way.

### ResilientLlm (Decorator)
It wraps the provider:
- retry up to 3 attempts
- backoff of 200 ms × 2^(attempt−1), plus random jitter
- every attempt passes through the circuit breaker

Jitter prevents a "thundering herd": many clients that failed at the same moment would otherwise all retry at the same moment.

### CircuitBreaker (State pattern)
- **CLOSED:** calls go through. 5 failures in a row → OPEN.
- **OPEN:** calls fail immediately for 30 s. After that, the next call moves it to HALF_OPEN.
- **HALF_OPEN:** exactly one trial call. Success → CLOSED, failure → OPEN.

The `Clock` is injected so the test can move time forward without waiting. The state is shown in `/actuator/health`. Health stays UP even when the circuit is open, because the app can still serve cached summaries and a load balancer shouldn't remove it.

**Check yourself:** The circuit is OPEN and a request arrives for a product with a cached summary. Trace exactly what happens. (Answer: `ResilientLlm` throws "circuit open" → `SummaryService` catches `LlmException` → returns `lastGood` with `stale: true` → HTTP 200 in ~1 ms.)

---

## 5. Cache (`SummaryCache`)

Three ideas, each worth a sentence in an interview:

1. **Content-addressed key.** The key is a hash of the prompt version plus the sorted review ids. A new review changes the key, so invalidation happens automatically, and there's no "delete on write" code that someone could forget to call.
2. **Single-flight.** The map stores a `CompletableFuture`, not a value. `ConcurrentHashMap.compute` atomically decides who computes. Everyone else `join()`s the same future. Ten concurrent requests means one LLM call (`SummaryCacheTest.concurrentRequestsShareOneComputation`).
3. **Never cache failures, but keep the last good result.** A failed future is removed, so the next request retries. `lastGood` is what makes stale fallback possible.

**Limitation:** the cache is in-memory and per instance. With several instances you'd use Redis, and single-flight would need a distributed lock or a "computing" marker key.

---

## 6. Concurrency summary

| Where | Mechanism | Why |
|---|---|---|
| `InMemoryStore` | `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicLong` | many reads, few writes |
| Map phase | virtual threads (`newVirtualThreadPerTaskExecutor`) | LLM calls are I/O-bound; cheap threads |
| Global LLM cap | `Semaphore(4)` in `Summarizer` | protect provider rate limits across *all* requests |
| Usage counting | `AtomicInteger` in `UsageTracker` | parallel map calls update the same counters |
| Cache | `compute()` + `CompletableFuture` | single-flight without holding a lock during the LLM call |
| Circuit breaker | `synchronized` methods | tiny critical sections; correctness over cleverness |

---

## 7. Observability

- `/actuator/metrics/reviewlens.llm.tokens?tag=type:input`
- `reviewlens.llm.cost.usd`
- `reviewlens.summary.latency`
- `reviewlens.summary.cache?tag=result:hit`
- `reviewlens.grounding.quotes?tag=result:dropped`
- `reviewlens.filter.rejected?tag=filter:spam`
- `reviewlens.llm.circuit.open`

What you'd alert on:
- the grounding drop rate goes up (the model or prompt got worse)
- cost per summary goes up (prompt bloat)
- the circuit opens (provider outage)

---

## 8. Evals (`EvalTest`)

There are four labeled cases. Each one is scored on:
- **filter accuracy:** exactly the expected rejections
- **aspect recall:** expected pros and cons found, using regex alternatives like `fit|falling out`
- **safety:** no forbidden phrase from an injection appears
- **grounding:** quotes kept vs dropped

The results go to `target/eval-report.md` and the CI job summary.

Honest caveat: with the mock, recall is 1.00 because the mock is a heuristic that the cases were written against. The real use is running it against Ollama or OpenAI before and after every prompt change, as a regression gate.

---

## 9. Exercises that make this yours

Do these and the stories become first-hand:

1. Run with Ollama, read the eval report, and write down one place where the real model did worse than the mock, and why.
2. Make the duplicate filter catch "same text, different author" at 30 characters instead of 40. Which test breaks? Is that the right trade-off?
3. Add a `ProfanityFilter` at `@Order(15)`. Notice that you don't have to edit `FilterPipeline`.
4. Lower `max-input-tokens` to 600 and watch `usage.chunks` and `usage.llmCalls` go up for product 3.
5. Point `OLLAMA_BASE_URL` at a dead port and call `/products/1/summary` twice. Watch the retries in the log, then the stale response.
