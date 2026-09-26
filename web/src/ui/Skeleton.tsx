/** Placeholder lines while data loads (hidden from screen readers; the caller sets `aria-busy`). */
export function Skeleton({ lines = 1 }: { lines?: number }) {
  return (
    <div className="skeleton" aria-hidden="true">
      {Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton-line" />)}
    </div>
  );
}
