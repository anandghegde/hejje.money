import { ReactNode } from 'react';
import { signed, tone } from './format';

interface StatProps {
  label: ReactNode;
  value: ReactNode;
  /** a change, shown signed and coloured by the profit/loss convention */
  delta?: number | null;
  /** how the delta is written; default `+1.23` */
  formatDelta?: (v: number) => string;
  'data-testid'?: string;
}

export function Stat({ label, value, delta, formatDelta = (v) => signed(v), ...rest }: StatProps) {
  return (
    <div className="stat" data-testid={rest['data-testid']}>
      <div className="stat-label">{label}</div>
      <div className="stat-value num">{value}</div>
      {delta !== undefined && delta !== null && (
        <div className={`stat-delta num tone-${tone(delta)}`}>{formatDelta(delta)}</div>
      )}
    </div>
  );
}
