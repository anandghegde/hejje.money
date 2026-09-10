import { useState } from 'react';
import { EMPTY_LEG, LegRow, legsYaml, replaceLegs } from '../lib/options';

/** Builds the `legs:` block of an options strategy and writes it into the definition (plan M5.4). */
export function LegBuilder({ yaml, onChange }: { yaml: string; onChange: (yaml: string) => void }) {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<LegRow[]>([{ ...EMPTY_LEG, hedgeFirst: true }]);
  const update = (i: number, patch: Partial<LegRow>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  if (!open) return <button onClick={() => setOpen(true)} style={{ marginTop: 8 }}>Option legs…</button>;
  return (
    <div data-testid="leg-builder" style={{ marginTop: 8, padding: 8, background: '#f4f6f8' }}>
      <b>Option legs</b> <small>(family: options; the strategy signals on its universe and trades these legs)</small>
      <table>
        <thead><tr><th>Action</th><th>Option</th><th>Strike</th><th></th><th>Expiry</th><th>Lots</th><th>Stop %</th><th>Target %</th><th>Hedge first</th><th></th></tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td><select value={r.action} onChange={(e) => update(i, { action: e.target.value as LegRow['action'] })}><option>buy</option><option>sell</option></select></td>
              <td><select value={r.option} onChange={(e) => update(i, { option: e.target.value as LegRow['option'] })}>
                {['directional', 'opposite', 'ce', 'pe'].map((o) => <option key={o}>{o}</option>)}</select></td>
              <td><select value={r.strike} onChange={(e) => update(i, { strike: e.target.value as LegRow['strike'] })}>
                {['atm', 'offset', 'delta'].map((o) => <option key={o}>{o}</option>)}</select></td>
              <td><input value={r.strikeValue} disabled={r.strike === 'atm'} placeholder={r.strike === 'delta' ? '0.3' : '100'} style={{ width: 60 }}
                onChange={(e) => update(i, { strikeValue: e.target.value })} /></td>
              <td><select value={r.expiry} onChange={(e) => update(i, { expiry: e.target.value as LegRow['expiry'] })}>
                {['nearest', 'next', 'monthly'].map((o) => <option key={o}>{o}</option>)}</select></td>
              <td><input type="number" min={1} value={r.lots} style={{ width: 50 }} onChange={(e) => update(i, { lots: Number(e.target.value) })} /></td>
              <td><input value={r.stopPct} style={{ width: 50 }} onChange={(e) => update(i, { stopPct: e.target.value })} /></td>
              <td><input value={r.targetPct} style={{ width: 50 }} onChange={(e) => update(i, { targetPct: e.target.value })} /></td>
              <td><input type="checkbox" checked={r.hedgeFirst} disabled={r.action === 'sell'} onChange={(e) => update(i, { hedgeFirst: e.target.checked })} /></td>
              <td><button onClick={() => setRows(rows.filter((_, j) => j !== i))} disabled={rows.length === 1}>×</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <button onClick={() => setRows([...rows, { ...EMPTY_LEG, action: 'sell', strike: 'offset', strikeValue: '100' }])} disabled={rows.length >= 4}>Add leg</button>
      <button onClick={() => onChange(replaceLegs(yaml, legsYaml(rows)))} style={{ marginLeft: 8 }}>Write legs into the definition</button>
      <pre style={{ fontSize: 12 }}>{legsYaml(rows)}</pre>
    </div>
  );
}
