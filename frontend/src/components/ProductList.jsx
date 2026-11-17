import { useState } from 'react';

export default function ProductList({ products, selectedId, onSelect, onAdd }) {
  const [name, setName] = useState('');
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      await onAdd(name.trim());
      setName('');
      setError(null);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <aside className="sidebar">
      <h2 className="section-label">Products</h2>
      <ul className="product-list">
        {products.map((p) => (
          <li key={p.id}>
            <button
              className={p.id === selectedId ? 'product active' : 'product'}
              onClick={() => onSelect(p.id)}
            >
              <span className="product-id">#{p.id}</span> {p.name}
            </button>
          </li>
        ))}
      </ul>
      <form className="add-product" onSubmit={submit}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="New product name"
          maxLength={200}
        />
        <button className="btn small" type="submit">Add</button>
      </form>
      {error && <div className="field-error">{error}</div>}
    </aside>
  );
}
