export default function GroundingPanel({ grounding }) {
  const { quotesChecked, quotesDropped, dropped } = grounding;

  return (
    <section className="card">
      <div className="card-head">
        <h2>Grounding check</h2>
        <span className={quotesDropped > 0 ? 'badge warn' : 'badge ok'}>
          {quotesChecked - quotesDropped}/{quotesChecked} verified
        </span>
      </div>
      <p className="hint">Every quote the model cites must appear in the review it cites.</p>
      {dropped.length === 0 ? (
        <p className="muted">All quotes matched their source review.</p>
      ) : (
        <ul className="dropped">
          {dropped.map((d) => (
            <li key={`${d.reviewId}-${d.text}`}>
              <s>“{d.text}”</s>
              <div className="detail">
                cited review #{d.reviewId}: {d.reason}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
