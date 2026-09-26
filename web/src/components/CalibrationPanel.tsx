import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Badge, Card, EmptyState, Field } from '../ui';

/** Plan M9.2: hit rate per probability bucket for a Jev purpose or a bot's confidence (docs/calibration.md). */
interface Bucket { lo: number; hi: number; n: number; hits: number; meanProbability: number | null; rate: number | null; wilsonLo: number | null; wilsonHi: number | null }
interface Report { purpose: string; version: string | null; n: number; none: number; pending: number; sessions: number; buckets: Bucket[];
  brier: number | null; ece: number | null; passes: boolean; reasons: string[] }
interface Purpose { purpose: string; version: string; predictions: number; labelled: number }

const f2 = (v: number | null) => (v == null ? '—' : v.toFixed(2));

export function CalibrationPanel() {
  const purposes = useQuery({ queryKey: ['calibration-purposes'], queryFn: () => request<Purpose[]>('/calibration/purposes') });
  const [chosen, setChosen] = useState<string>('');
  const pick = chosen || purposes.data?.[0]?.purpose || '';
  const [purpose, version] = pick.split('@');
  const report = useQuery({
    queryKey: ['calibration', pick],
    queryFn: () => request<Report>(`/calibration?purpose=${encodeURIComponent(purpose)}${version ? `&version=${encodeURIComponent(version)}` : ''}`),
    enabled: pick !== '',
  });
  const r = report.data;
  return (
    <Card title="Calibration" data-testid="calibration">
      <div className="stack">
        {(purposes.data ?? []).length === 0 ? <EmptyState title="No predictions recorded yet." /> : (
          <Field label="Purpose">
            <select value={pick} onChange={(e) => setChosen(e.target.value)}>
              {(purposes.data ?? []).map((p) => (
                <option key={p.purpose + p.version} value={`${p.purpose}@${p.version}`}>{p.purpose} v{p.version} ({p.labelled}/{p.predictions} labelled)</option>
              ))}
            </select>
          </Field>
        )}
        {r && (
          <>
            <p className="cluster">{r.n} labelled, {r.none} none, {r.pending} pending over {r.sessions} sessions · Brier {f2(r.brier)} · ECE {f2(r.ece)} ·
              <Badge tone={r.passes ? 'profit' : 'warning'}>{r.passes ? 'passes the bar' : 'does not pass'}</Badge>{r.passes ? '' : ` ${r.reasons.join('; ')}`}</p>
            <div className="table-scroll">
              <table data-testid="calibration-table">
                <thead><tr><th>Bucket</th><th className="num">N</th><th className="num">Hits</th><th className="num">Mean p</th><th className="num">Rate</th><th className="num">Wilson 95%</th></tr></thead>
                <tbody>
                  {r.buckets.map((b) => (
                    <tr key={b.lo}><td className="num">{b.lo.toFixed(1)}–{b.hi.toFixed(1)}</td><td className="num">{b.n}</td><td className="num">{b.hits}</td><td className="num">{f2(b.meanProbability)}</td><td className="num">{f2(b.rate)}</td>
                      <td className="num">{b.wilsonLo == null ? '—' : `${f2(b.wilsonLo)}–${f2(b.wilsonHi)}`}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </Card>
  );
}
