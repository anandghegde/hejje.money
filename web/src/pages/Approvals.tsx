import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, request } from '../api/client';
import { Approval } from '../api/types';
import { proposalRows, timeLeft } from '../lib/approvals';

const STATUS_COLOR: Record<string, string> = { PENDING: '#ef6c00', APPROVED: '#2e7d32', REJECTED: '#616161', EXPIRED: '#9e9e9e', FAILED: '#c62828' };

function ApprovalCard({ a, now, busy, onDecide }: { a: Approval; now: number; busy: boolean; onDecide: (a: Approval, action: 'approve' | 'reject', reason?: string) => void }) {
  const [reason, setReason] = useState('');
  const left = timeLeft(a.expiresAt, now);
  return (
    <div data-testid="approval-card" style={{ border: '1px solid #e0e0e0', borderRadius: 6, padding: 12, marginBottom: 12, background: '#fffaf3' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <b>{a.summary}</b>
        <span style={{ color: left === 'expired' ? '#c62828' : '#616161' }}>expires in {left}</span>
      </div>
      <div style={{ fontSize: 13, color: '#616161', margin: '4px 0 8px' }}>
        {a.kind} · requested by {a.requestedBy} ({a.requestedByType === 'CLIENT' ? 'agent key' : 'Hejje AI'}) · {new Date(a.createdAt).toLocaleTimeString()}
      </div>
      {a.rationale && <p style={{ margin: '4px 0' }}>“{a.rationale}”</p>}
      <table style={{ fontSize: 13 }}><tbody>
        {proposalRows(a).map(([k, v]) => <tr key={k}><td style={{ paddingRight: 12, color: '#616161' }}>{k}</td><td>{v}</td></tr>)}
      </tbody></table>
      {a.policy && <p style={{ fontSize: 13 }}>Policy: <b>{a.policy.decision}</b> — {a.policy.reason}</p>}
      {a.risk && (
        <details open={a.risk.outcome !== 'APPROVED'}>
          <summary>Risk dry run: <b style={{ color: a.risk.outcome === 'APPROVED' ? '#2e7d32' : '#c62828' }}>{a.risk.outcome}</b> ({a.risk.checks.length} checks)</summary>
          <ul style={{ fontSize: 13 }}>{a.risk.checks.map((c) => <li key={c.name} style={{ color: c.passed ? '#2e7d32' : '#c62828' }}>{c.passed ? '✓' : '✗'} {c.name}{c.message ? ` — ${c.message}` : ''}</li>)}</ul>
        </details>
      )}
      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
        <button data-testid={`approve-${a.id}`} disabled={busy || left === 'expired'} onClick={() => onDecide(a, 'approve')}>Approve</button>
        <input placeholder="reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} disabled={busy} />
        <button data-testid={`reject-${a.id}`} disabled={busy} onClick={() => onDecide(a, 'reject', reason)}>Reject</button>
      </div>
    </div>
  );
}

export function Approvals() {
  const qc = useQueryClient();
  const { data: pending, error } = useQuery({ queryKey: ['approvals', 'PENDING'], queryFn: () => request<Approval[]>('/approvals?status=PENDING'),
    refetchInterval: 5000, retry: false });
  const { data: recent } = useQuery({ queryKey: ['approvals', 'ALL'], queryFn: () => request<Approval[]>('/approvals?status=ALL&limit=20'),
    refetchInterval: 10000, retry: false });
  const [now, setNow] = useState(Date.now());
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);

  async function decide(a: Approval, action: 'approve' | 'reject', reason?: string) {
    setBusy(a.id);
    try {
      const r = await request<Approval>(`/approvals/${a.id}/${action}`, { method: 'POST', idempotent: true, body: action === 'reject' ? { reason } : undefined });
      setMessage(`${r.status}: ${r.summary}${r.result?.orderId ? ` → order ${r.result.orderId} (${r.result.state})` : ''}`);
    } catch (e) {
      const err = e as ApiError;
      const reasons: string[] = err.problem?.errors ?? [];
      setMessage(`${action} failed: ${err.message}${reasons.length ? ` — ${reasons.join('; ')}` : ''}`);
    } finally {
      setBusy(null);
      qc.invalidateQueries({ queryKey: ['approvals'] });
    }
  }

  if (error) return <p>Approvals unavailable: {(error as Error).message}</p>;
  const decided = (recent ?? []).filter((a) => a.status !== 'PENDING');
  return (
    <div>
      <h1>Approvals</h1>
      <p style={{ color: '#616161', marginTop: -8 }}>Agents propose; approving re-runs the policy and risk checks, then sends the order through the normal pipeline.</p>
      {message && <p data-testid="approval-message" style={{ background: '#f4f6f8', padding: 8, borderRadius: 4 }}>{message}</p>}
      {(pending ?? []).length === 0 && <p data-testid="no-approvals">Nothing is waiting for approval.</p>}
      {(pending ?? []).map((a) => <ApprovalCard key={a.id} a={a} now={now} busy={busy === a.id} onDecide={decide} />)}
      <h3>Recently decided</h3>
      <table data-testid="decided-approvals" style={{ fontSize: 13, borderCollapse: 'collapse' }}>
        <thead><tr><th align="left">When</th><th align="left">Status</th><th align="left">Summary</th><th align="left">By</th><th align="left">Note / result</th></tr></thead>
        <tbody>
          {decided.map((a) => (
            <tr key={a.id}>
              <td>{new Date(a.decidedAt ?? a.createdAt).toLocaleTimeString()}</td>
              <td style={{ color: STATUS_COLOR[a.status], fontWeight: 600 }}>{a.status}</td>
              <td>{a.summary}</td><td>{a.decidedBy ?? '—'}</td>
              <td>{a.decisionNote ?? (a.result?.orderId ? `order ${a.result.orderId} ${a.result.state}` : '—')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
