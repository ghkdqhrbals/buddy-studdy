export function EmptyState({ title, message, compact = false }: { title: string; message?: string; compact?: boolean }) {
  return (
    <div className={compact ? "empty-state compact-empty" : "empty-state"}>
      <h2>{title}</h2>
      {message ? <p>{message}</p> : null}
    </div>
  );
}
