// Thin wrapper over the ReviewLens REST API. Errors come back as Spring ProblemDetail JSON
// ({ status, detail, ... }), so we surface `detail` and never show raw provider errors.

async function request(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const message = body?.detail || `Request failed (${res.status})`;
    const error = new Error(message);
    error.status = res.status;
    throw error;
  }
  return body;
}

export const api = {
  products: () => request('/products'),
  createProduct: (name) => request('/products', { method: 'POST', body: JSON.stringify({ name }) }),
  reviews: (id) => request(`/products/${id}/reviews`),
  addReview: (id, review) =>
    request(`/products/${id}/reviews`, { method: 'POST', body: JSON.stringify(review) }),
  summary: (id, refresh = false) => request(`/products/${id}/summary${refresh ? '?refresh=true' : ''}`),
  // Actuator answers 503 with a JSON body when something is DOWN; we still want that body.
  health: () => fetch('/actuator/health').then((res) => res.json()),
};
