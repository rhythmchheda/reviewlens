import { useCallback, useEffect, useState } from 'react';
import { api } from './api.js';
import ProductList from './components/ProductList.jsx';
import SummaryPanel from './components/SummaryPanel.jsx';
import FilteringPanel from './components/FilteringPanel.jsx';
import GroundingPanel from './components/GroundingPanel.jsx';
import ReviewList from './components/ReviewList.jsx';
import AddReviewForm from './components/AddReviewForm.jsx';
import HealthBadge from './components/HealthBadge.jsx';

export default function App() {
  const [products, setProducts] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [reviews, setReviews] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [highlightId, setHighlightId] = useState(null);
  const [health, setHealth] = useState(null);

  const refreshHealth = useCallback(() => {
    api.health().then(setHealth).catch(() => setHealth(null));
  }, []);

  useEffect(() => {
    api.products()
      .then((list) => {
        setProducts(list);
        if (list.length > 0) setSelectedId(list[0].id);
      })
      .catch((e) => setError(e.message));
    refreshHealth();
  }, [refreshHealth]);

  // Load reviews and summary for the selected product. The summary call is the slow one
  // (it may hit the LLM), so reviews render first and the summary fills in after.
  const load = useCallback(
    async (id, refresh = false) => {
      setLoading(true);
      setError(null);
      try {
        setReviews(await api.reviews(id));
        setSummary(await api.summary(id, refresh));
      } catch (e) {
        setSummary(null);
        setError(e.message);
      } finally {
        setLoading(false);
        refreshHealth();
      }
    },
    [refreshHealth],
  );

  useEffect(() => {
    if (selectedId != null) {
      setSummary(null);
      setHighlightId(null);
      load(selectedId);
    }
  }, [selectedId, load]);

  async function handleAddProduct(name) {
    const product = await api.createProduct(name);
    setProducts((list) => [...list, product]);
    setSelectedId(product.id);
  }

  async function handleAddReview(review) {
    await api.addReview(selectedId, review);
    // The cache key covers the review set, so a new review naturally misses the cache.
    await load(selectedId);
  }

  const rejectedById = new Map((summary?.filtering.rejected ?? []).map((r) => [r.reviewId, r]));
  const selected = products.find((p) => p.id === selectedId);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden="true">◎</span> ReviewLens
        </div>
        <HealthBadge health={health} />
      </header>

      <div className="layout">
        <ProductList
          products={products}
          selectedId={selectedId}
          onSelect={setSelectedId}
          onAdd={handleAddProduct}
        />

        <main className="main">
          {selected && (
            <div className="main-head">
              <h1>{selected.name}</h1>
              <button className="btn" onClick={() => load(selectedId, true)} disabled={loading}>
                {loading ? 'Summarizing…' : 'Regenerate summary'}
              </button>
            </div>
          )}

          {error && <div className="alert">{error}</div>}

          {loading && !summary && <div className="card muted">Generating summary…</div>}

          {summary && (
            <>
              <SummaryPanel summary={summary} onQuoteClick={setHighlightId} />
              <div className="grid-2">
                <FilteringPanel filtering={summary.filtering} onSelect={setHighlightId} />
                <GroundingPanel grounding={summary.grounding} />
              </div>
            </>
          )}

          {selected && (
            <div className="grid-2 bottom">
              <ReviewList reviews={reviews} rejectedById={rejectedById} highlightId={highlightId} />
              <AddReviewForm onSubmit={handleAddReview} />
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
