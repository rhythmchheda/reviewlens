export default function FilteringPanel({ filtering, onSelect }) {
  const { rejected } = filtering;

  return (
    <section className="card">
      <div className="card-head">
        <h2>Filtered out</h2>
        <span className="badge neutral">{rejected.length}</span>
      </div>
      <p className="hint">Removed before the prompt is built, so the model never sees them.</p>
      {rejected.length === 0 ? (
        <p className="muted">Nothing filtered for this product.</p>
      ) : (
        <ul className="rejected">
          {rejected.map((r) => (
            <li key={r.reviewId}>
              <button className="rejected-item" onClick={() => onSelect(r.reviewId)}>
                <span className={`tag tag-${r.filter}`}>{r.filter}</span>
                <span>
                  <strong>#{r.reviewId}</strong> by {r.author}
                  <div className="detail">{r.detail}</div>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
