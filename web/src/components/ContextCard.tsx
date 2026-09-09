import { useState } from 'react';
import { StrategyContext } from '../api/types';

const DOT: Record<string, string> = { GREEN: '🟢', AMBER: '🟠', RED: '🔴', UNKNOWN: '⚪' };

/** PRD 19 Strategy Context Card with expandable evidence per row. */
export function ContextCard({ context }: { context: StrategyContext }) {
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div data-testid="context-card" style={{ border: '1px solid #ddd', borderRadius: 6, padding: 12, marginTop: 12, maxWidth: 640 }}>
      <div style={{ fontWeight: 700, marginBottom: 6 }}>CONTEXT</div>
      <table style={{ width: '100%' }}>
        <tbody>
          {context.items.map((item) => (
            <tr key={item.name} onClick={() => setOpen(open === item.name ? null : item.name)} style={{ cursor: 'pointer' }}>
              <td>{item.name}</td>
              <td>{DOT[item.status]} {item.value}{item.delta != null ? ` (${item.delta >= 0 ? '+' : ''}${item.delta})` : ''}</td>
              <td style={{ fontSize: 12, color: '#666' }}>{open === item.name ? item.evidence.join(' · ') : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ marginTop: 6 }}>Next event: <b>{context.nextEvent ?? '—'}</b></div>
      <div>Net context impact: <b>{context.netImpact >= 0 ? '+' : ''}{context.netImpact} point{Math.abs(context.netImpact) === 1 ? '' : 's'}</b> · <a href="/pulse">Pulse</a></div>
    </div>
  );
}
