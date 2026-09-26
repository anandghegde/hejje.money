import { useState } from 'react';
import { EMPTY_LEG, LegRow, legsYaml, replaceLegs } from '../lib/options';
import { Button } from '../ui';

/** Builds the `legs:` block of an options strategy and writes it into the definition (plan M5.4). */
export function LegBuilder({ yaml, onChange }: { yaml: string; onChange: (yaml: string) => void }) {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<LegRow[]>([{ ...EMPTY_LEG, hedgeFirst: true }]);
  const update = (i: number, patch: Partial<LegRow>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  if (!open) return <div><Button onClick={() => setOpen(true)}>Option legs…</Button></div>;
  return (
    <div data-testid="leg-builder" className="panel stack-sm">
      <div><b>Option legs</b> <small className="muted">(family: options; the strategy signals on its universe and trades these legs)</small></div>
      <div className="table-scroll">
        <table className="compact-inputs">
          <thead><tr><th>Action</th><th>Option</th><th>Strike</th><th></th><th>Expiry</th><th>Lots</th><th>Stop %</th><th>Target %</th><th>Hedge first</th><th></th></tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td><select value={r.action} onChange={(e) => update(i, { action: e.target.value as LegRow['action'] })}><option>buy</option><option>sell</option></select></td>
                <td><select value={r.option} onChange={(e) => update(i, { option: e.target.value as LegRow['option'] })}>
                  {['directional', 'opposite', 'ce', 'pe'].map((o) => <option key={o}>{o}</option>)}</select></td>
                <td><select value={r.strike} onChange={(e) => update(i, { strike: e.target.value as LegRow['strike'] })}>
                  {['atm', 'offset', 'delta'].map((o) => <option key={o}>{o}</option>)}</select></td>
                <td><input className="input-sm" value={r.strikeValue} disabled={r.strike === 'atm'} placeholder={r.strike === 'delta' ? '0.3' : '100'}
                  onChange={(e) => update(i, { strikeValue: e.target.value })} /></td>
                <td><select value={r.expiry} onChange={(e) => update(i, { expiry: e.target.value as LegRow['expiry'] })}>
                  {['nearest', 'next', 'monthly'].map((o) => <option key={o}>{o}</option>)}</select></td>
                <td><input className="input-xs" type="number" min={1} value={r.lots} onChange={(e) => update(i, { lots: Number(e.target.value) })} /></td>
                <td><input className="input-xs" value={r.stopPct} onChange={(e) => update(i, { stopPct: e.target.value })} /></td>
                <td><input className="input-xs" value={r.targetPct} onChange={(e) => update(i, { targetPct: e.target.value })} /></td>
                <td><input type="checkbox" checked={r.hedgeFirst} disabled={r.action === 'sell'} onChange={(e) => update(i, { hedgeFirst: e.target.checked })} /></td>
                <td><Button size="sm" onClick={() => setRows(rows.filter((_, j) => j !== i))} disabled={rows.length === 1} aria-label={`remove leg ${i + 1}`}>×</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="cluster">
        <Button onClick={() => setRows([...rows, { ...EMPTY_LEG, action: 'sell', strike: 'offset', strikeValue: '100' }])} disabled={rows.length >= 4}>Add leg</Button>
        <Button onClick={() => onChange(replaceLegs(yaml, legsYaml(rows)))}>Write legs into the definition</Button>
      </div>
      <pre className="code-block">{legsYaml(rows)}</pre>
    </div>
  );
}
