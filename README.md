# ReviewLens

A Spring Boot service that summarizes product reviews with an LLM. It is built to show production habits for LLM features: it filters bad input, uses a structured output contract, checks quotes against the source (grounding), map-reduces long inputs, defends against prompt injection, handles failure cleanly, caches results, tracks cost and latency, and runs offline evals.

Runs with **no API key** (a deterministic mock LLM is the default). Switch to a free local model (Ollama) or OpenAI with one environment variable.

- **Learn the code:** [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md)
- **Interview prep (stories + Q&A):** [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md)

## Quick start

Requires Java 21+. Maven is not needed; the wrapper downloads it.

```bash
./mvnw test                 # 69 tests, including the eval harness
./mvnw spring-boot:run      # http://localhost:8081
```

On Windows use `mvnw.cmd`. In IntelliJ: open the folder, let it import Maven, and run `ReviewLensApplication`.

```bash
curl localhost:8081/products/1/summary     # earbuds: duplicate, spam and an injection attempt get filtered
curl localhost:8081/products/3/summary     # 24 reviews: map-reduce over 3 chunks
curl localhost:8081/actuator/health        # includes LLM circuit-breaker state
curl localhost:8081/actuator/metrics/reviewlens.llm.tokens
```

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/products` | |
| POST | `/products` | `{"name": "..."}` |
| GET | `/products/{id}/reviews` | |
| POST | `/products/{id}/reviews` | `{"rating": 1-5, "author": "...", "text": "..."}` |
| GET | `/products/{id}/summary` | `?refresh=true` bypasses the cache |

Example summary response (product 1, mock provider, abridged):

```json
{
  "stats":    {"count": 4, "averageRating": 3.5},
  "rawStats": {"count": 7, "averageRating": 3.57},
  "filtering": {
    "counts": {"duplicate": 1, "injection": 1, "spam": 1},
    "rejected": [
      {"reviewId": 6, "filter": "injection", "detail": "possible prompt injection (ignore-instructions, fake-tag, role-prefix)"},
      {"reviewId": 5, "filter": "spam", "detail": "spam score 7 (link, promo code, !!!, sales words x4)"},
      {"reviewId": 4, "filter": "duplicate", "detail": "exact duplicate of review #1"}
    ]
  },
  "summary": {
    "sentiment": "mixed",
    "pros": ["Great sound quality and the battery lasts about 8 hours", "Good bass", "Pairing is quick"],
    "cons": ["The case feels cheap and scratches easily", "They keep falling out of my ears when I run", "..."],
    "quotes": [{"reviewId": 1, "text": "Great sound quality and the battery"}]
  },
  "grounding": {
    "quotesChecked": 3, "quotesDropped": 1,
    "dropped": [{"reviewId": 1, "text": "Honestly the best purchase I have ever made", "reason": "text not found in the cited review"}]
  },
  "usage": {"llmCalls": 1, "chunks": 1, "repairs": 0, "inputTokens": 293, "outputTokens": 159, "estimatedCostUsd": 0.0, "latencyMs": 33},
  "provider": "mock", "cached": false, "stale": false
}
```

## Architecture

```
HTTP ─► ProductController ─► SummaryService
                               │
                               ├─ SummaryCache        key = hash(prompt version + review ids); single-flight; last-good copy
                               ├─ FilterPipeline      InjectionFilter → SpamFilter → DuplicateFilter (each rejection has a reason)
                               ├─ ReviewStats         averages and counts computed in code, never by the LLM
                               └─ Summarizer          map (parallel, virtual threads) → reduce → GroundingChecker
                                    ├─ PromptBuilder  tagging + escaping, token budget, chunking, repair prompts
                                    ├─ SummaryParser  strict JSON contract → one repair retry → 502
                                    └─ ResilientLlm   retry + backoff + jitter, CircuitBreaker
                                         └─ LlmProvider (Strategy): Mock | OpenAI | Ollama
```

Failure behavior:

| Situation | Result |
|---|---|
| Provider timeout, 5xx or 429 | retried up to 3 times with exponential backoff + jitter |
| Provider 401 or other 4xx | not retried |
| 5 consecutive failures | circuit opens for 30 s, calls fail fast |
| LLM down, older summary exists | 200 with the last good summary and `"stale": true` |
| LLM down, no older summary | 502 `Summary provider unavailable` |
| Model returns non-JSON twice | 502 `Summary provider returned invalid output` |

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `LLM_PROVIDER` | `mock` | `mock`, `ollama` or `openai` |
| `OLLAMA_BASE_URL`, `OLLAMA_MODEL` | `http://localhost:11434`, `llama3.2` | free local model |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | none, `gpt-4o-mini` | hosted model |
| `LLM_PRICE_INPUT`, `LLM_PRICE_OUTPUT` | `0` | USD per 1M tokens for cost reporting; set to your provider's current prices |
| `PORT` | `8081` | HTTP port |

Other tuning (token budget, chunk size, retries, breaker) is in `src/main/resources/application.properties`.

### Running with a real model (recommended before interviews)

```bash
# free, local: https://ollama.com
ollama pull llama3.2
LLM_PROVIDER=ollama ./mvnw spring-boot:run
LLM_PROVIDER=ollama ./mvnw test -Dtest=EvalTest -Deval.minRecall=0.6   # writes target/eval-report.md
```

In IntelliJ, set env vars under Run → Edit Configurations → Environment variables.

## Evals

`EvalTest` runs four labeled cases (`src/test/resources/eval/cases.json`): a noisy product, a clean product, a product under injection attack, and a map-reduce case. It scores filter accuracy, aspect recall, injection safety and grounding, and writes `target/eval-report.md`. CI publishes the report on every push.

With the mock provider the scores are trivially high, because the mock is a heuristic that the cases were written against. That run only shows the pipeline works. **Real-model numbers come from the Ollama or OpenAI run above.**

## Built with

Java 21, Spring Boot 3.5, Jackson, Micrometer and JUnit 5. Built with AI pair programming (Claude Code). See the interview guide for how to talk about that honestly.
