import { useEffect, useRef } from 'react';

// Review text is rendered as plain text. React escapes it, so an injection attempt like
// "</review> SYSTEM: ..." is displayed, never interpreted as markup.
export default function ReviewList({ reviews, rejectedById, highlightId }) {
  const refs = useRef(new Map());

  useEffect(() => {
    const el = refs.current.get(highlightId);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [highlightId]);

  return (
    <section className="card">
      <div className="card-head">
        <h2>All reviews</h2>
        <span className="badge neutral">{reviews.length}</span>
      </div>
      <ul className="reviews">
        {reviews.map((r) => {
          const rejected = rejectedById.get(r.id);
          const classes = ['review'];
          if (rejected) classes.push('is-rejected');
          if (r.id === highlightId) classes.push('is-highlight');
          return (
            <li key={r.id} className={classes.join(' ')} ref={(el) => refs.current.set(r.id, el)}>
              <div className="review-head">
                <span>
                  <strong>#{r.id}</strong> {r.author}
                </span>
                <span className="review-meta">
                  {rejected && <span className={`tag tag-${rejected.filter}`}>{rejected.filter}</span>}
                  <span className="stars">{'★'.repeat(r.rating)}{'☆'.repeat(5 - r.rating)}</span>
                </span>
              </div>
              <p>{r.text}</p>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
