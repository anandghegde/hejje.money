import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, request } from '../api/client';
import { Backtest, StrategyDraft } from '../api/types';
import { lineDiff } from '../lib/diff';

const COLOR = { ' ': 'inherit', '-': '#c62828', '+': '#2e7d32' } as const;

/** "Describe a strategy" (plan M4.6): the LLM drafts YAML, Hejje validates it; the draft is DRAFT and needs backtest, validation and human status changes. */
export function DescribeStrategy({ strategyId }: { strategyId: string | null }) {
  const nav = useNavigate();
  const [description, setDescription] = useState('');
  const [asVersion, setAsVersion] = useState(false);
  const [draft, setDraft] = useState<StrategyDraft | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  async function build() {
    setBusy(true);
    setMessage('');
    try {
      setDraft(await request<StrategyDraft>('/strategies/drafts', { method: 'POST', body: { description, strategy: asVersion && strategyId ? strategyId : undefined } }));
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 422 && err.problem?.attempts) setDraft(err.problem as StrategyDraft);
      else setMessage(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function backtest() {
    if (!draft?.versionId) return;
    try {
      const b = await request<Backtest>('/backtests', { method: 'POST', body: { versionId: draft.versionId, from: '2024-01-01', to: new Date().toISOString().slice(0, 10), splits: { type: 'FIXED' } } });
      setMessage(`Backtest ${b.status} — follow it in the Lab`);
    } catch (e) { setMessage((e as Error).message); }
  }

  async function discard() {
    if (!draft?.strategyId || !draft.version) return;
    try {
      await request(`/strategies/${draft.strategyId}/versions/${draft.version}/status`, { method: 'POST', body: { status: 'RETIRED', note: 'discarded NL draft' } });
      setMessage(`${draft.slug} v${draft.version} retired`);
      setDraft(null);
    } catch (e) { setMessage((e as Error).message); }
  }

  return (
    <div data-testid="describe-strategy" style={{ border: '1px solid #e0e0e0', borderRadius: 6, padding: 12, marginBottom: 16 }}>
      <h3 style={{ marginTop: 0 }}>Describe a strategy</h3>
      <textarea data-testid="describe-input" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000}
        placeholder="Buy NIFTY when price closes above the first 15-minute high, above VWAP, with relative volume over 1.5. Stop below the opening range, target 2R."
        style={{ width: '100%', height: 70 }} />
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 4 }}>
        <button onClick={build} disabled={busy || description.trim().length < 10}>{busy ? 'Drafting…' : 'Draft it'}</button>
        {strategyId && <label><input type="checkbox" checked={asVersion} onChange={(e) => setAsVersion(e.target.checked)} /> as the next version of this strategy</label>}
        <span style={{ color: '#616161', fontSize: 12 }}>Drafts are saved as DRAFT and cannot trade until backtested, validated and promoted by you.</span>
      </div>
      {message && <p data-testid="describe-message">{message}</p>}
      {draft && (
        <div style={{ display: 'flex', gap: 16, marginTop: 8 }}>
          <div style={{ flex: 1 }}>
            {draft.created
              ? <p data-testid="draft-created"><b>{draft.slug} v{draft.version}</b> — {draft.status} ({draft.changeNote}), valid after {draft.attempts.length} attempt(s)</p>
              : <p style={{ color: '#c62828' }}>No valid definition after {draft.attempts.length} attempts: {draft.errors.join('; ')}</p>}
            <ol data-testid="draft-rules">{draft.rules.map((r, i) => <li key={i} style={{ listStyle: r.startsWith('•') ? 'none' : undefined }}>{r}</li>)}</ol>
            {draft.created && (
              <div style={{ display: 'flex', gap: 8 }}>
                <button onClick={backtest}>Run backtest</button>
                <button onClick={() => nav(`/lab?strategyId=${draft.strategyId}&version=${draft.version}`)}>Edit</button>
                <button onClick={discard}>Discard</button>
              </div>
            )}
          </div>
          <div style={{ flex: 1 }}>
            {draft.parentYaml ? (
              <pre data-testid="draft-diff" style={{ fontSize: 12, background: '#fafafa', padding: 8 }}>
                {lineDiff(draft.parentYaml, draft.yaml).map((l, i) => <div key={i} style={{ color: COLOR[l.op] }}>{l.op} {l.text}</div>)}
              </pre>
            ) : <pre data-testid="draft-yaml" style={{ fontSize: 12, background: '#fafafa', padding: 8 }}>{draft.yaml}</pre>}
          </div>
        </div>
      )}
    </div>
  );
}
