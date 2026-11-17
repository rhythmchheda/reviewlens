function Stars({ value }) {
  return <span className="stars" aria-label={`${value} out of 5`}>{value.toFixed(2)} ★</span>;
}

export default function SummaryPanel({ summary, onQuoteClick }) {
  const { summary: s, stats, rawStats, usage } = summary;
  const filteredOut = rawStats.count - stats.count;

  return (
    <section className="card">
      <div className="card-head">
        <h2>Summary</h2>
        <div className="badges">
          <span className={`badge sentiment-${s.sentiment}`}>{s.sentiment}</span>
          <span className="badge neutral">{summary.provider}</span>
          {summary.cached && <span className="badge info">cached</span>}
          {summary.stale && (
            <span className="badge warn" title="The LLM failed, so this is the last good summary">
              stale
            </span>
          )}
        </div>
      </div>

      <p className="overview">{s.overview}</p>

      <div className="grid-2">
        <div>
          <h3 className="pros-title">Pros</h3>
          <ul className="pc-list">{s.pros.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
        <div>
          <h3 className="cons-title">Cons</h3>
          <ul className="pc-list">{s.cons.map((c) => <li key={c}>{c}</li>)}</ul>
        </div>
      </div>

      {s.quotes.length > 0 && (
        <>
          <h3>Verified quotes</h3>
          <ul className="quotes">
            {s.quotes.map((q) => (
              <li key={`${q.reviewId}-${q.text}`}>
                <button className="quote" onClick={() => onQuoteClick(q.reviewId)}>
                  “{q.text}”<span className="cite">review #{q.reviewId}</span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="stat-row">
        <div className="stat">
          <div className="stat-label">Rating (clean)</div>
          <div className="stat-value"><Stars value={stats.averageRating} /></div>
          <div className="stat-sub">{stats.count} reviews</div>
        </div>
        <div className="stat">
          <div className="stat-label">Rating (raw)</div>
          <div className="stat-value"><Stars value={rawStats.averageRating} /></div>
          <div className="stat-sub">{rawStats.count} reviews, {filteredOut} filtered</div>
        </div>
        <div className="stat">
          <div className="stat-label">LLM calls</div>
          <div className="stat-value">{usage.llmCalls}</div>
          <div className="stat-sub">{usage.chunks} chunk{usage.chunks === 1 ? '' : 's'}, {usage.repairs} repairs</div>
        </div>
        <div className="stat">
          <div className="stat-label">Tokens</div>
          <div className="stat-value">{usage.inputTokens + usage.outputTokens}</div>
          <div className="stat-sub">{usage.inputTokens} in / {usage.outputTokens} out</div>
        </div>
        <div className="stat">
          <div className="stat-label">Cost / latency</div>
          <div className="stat-value">${usage.estimatedCostUsd.toFixed(4)}</div>
          <div className="stat-sub">{usage.latencyMs} ms</div>
        </div>
      </div>
      <p className="footnote">Ratings and counts are computed in code, not by the model.</p>
    </section>
  );
}
