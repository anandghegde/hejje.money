import { useState } from 'react';
import { StrategyContext } from '../api/types';
import { Badge, BadgeTone } from '../ui';
import '../styles/research.css';

const TONE: Record<string, BadgeTone> = { GREEN: 'profit', AMBER: 'warning', RED: 'loss', UNKNOWN: 'neutral' };
const DOT: Record<string, string> = { GREEN: '●', AMBER: '◐', RED: '▲', UNKNOWN: '○' };

/** PRD 19 Strategy Context Card with expandable evidence per row. */
export function ContextCard({ context }: { context: StrategyContext }) {
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div data-testid="context-card" className="context-card">
      <div className="context-card-title">CONTEXT</div>
      <div className="table-scroll">
        <table className="context-table">
          <tbody>
            {context.items.map((item) => (
              <tr key={item.name} className="clickable" onClick={() => setOpen(open === item.name ? null : item.name)}>
                <td>{item.name}</td>
                <td>
                  <Badge tone={TONE[item.status] ?? 'neutral'}>{DOT[item.status] ?? '○'} {item.status}</Badge>{' '}
                  {item.value}{item.delta != null ? ` (${item.delta >= 0 ? '+' : ''}${item.delta})` : ''}
                </td>
                <td className="why">{open === item.name ? item.evidence.join(' · ') : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div>Next event: <b>{context.nextEvent ?? '—'}</b></div>
      <div>Net context impact: <b>{context.netImpact >= 0 ? '+' : ''}{context.netImpact} point{Math.abs(context.netImpact) === 1 ? '' : 's'}</b> · <a href="/pulse">Pulse</a></div>
    </div>
  );
}
