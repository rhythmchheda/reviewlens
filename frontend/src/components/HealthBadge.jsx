// Shows the LLM circuit breaker state from /actuator/health (CLOSED = calls flowing normally).
export default function HealthBadge({ health }) {
  if (!health) return <span className="badge warn">backend unreachable</span>;
  const llm = health.components?.llmCircuit?.details;
  const circuit = llm?.circuit ?? 'UNKNOWN';
  const cls = circuit === 'CLOSED' ? 'ok' : circuit === 'OPEN' ? 'bad' : 'warn';
  return (
    <span className={`badge ${cls}`} title="LLM circuit breaker state">
      {llm?.provider ?? 'llm'} · circuit {circuit}
    </span>
  );
}
