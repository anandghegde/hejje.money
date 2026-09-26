import { Fragment, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { ApiError, request } from '../api/client';
import { Approval } from '../api/types';
import { proposalRows, timeLeft } from '../lib/approvals';
import { Badge, BadgeTone, Button, Card, DataTable, EmptyState, Page, PHONE, useMediaQuery } from '../ui';
import '../styles/trading.css';

const STATUS_TONE: Record<string, BadgeTone> = { PENDING: 'warning', APPROVED: 'profit', REJECTED: 'neutral', EXPIRED: 'neutral', FAILED: 'loss' };

const note = (a: Approval) => a.decisionNote ?? (a.result?.orderId ? `order ${a.result.orderId} ${a.result.state}` : '—');

function ApprovalCard({ a, now, busy, onDecide }: { a: Approval; now: number; busy: boolean; onDecide: (a: Approval, action: 'approve' | 'reject', reason?: string) => void }) {
  const [reason, setReason] = useState('');
  const left = timeLeft(a.expiresAt, now);
  return (
    <Card
      data-testid="approval-card"
      className="approval-card"
      title={a.summary}
      actions={<Badge tone={left === 'expired' ? 'loss' : 'warning'}>expires in {left}</Badge>}
    >
      <div className="stack approval-body">
        <div className="muted text-sm">
          {a.kind} · requested by {a.requestedBy} ({a.requestedByType === 'CLIENT' ? 'agent key' : a.requestedByType === 'STRATEGY' ? 'strategy, held by the AUTO policy' : 'Hejje AI'}) · {new Date(a.createdAt).toLocaleTimeString()}
        </div>
        {a.rationale && <p className="approval-rationale">“{a.rationale}”</p>}
        <dl className="kv">
          {proposalRows(a).map(([k, v]) => <Fragment key={k}><dt>{k}</dt><dd>{v}</dd></Fragment>)}
        </dl>
        {a.policy && <p className="text-sm">Policy: <b>{a.policy.decision}</b> — {a.policy.reason}</p>}
        {a.risk && (
          <details open={a.risk.outcome !== 'APPROVED'}>
            <summary>Risk dry run: <Badge tone={a.risk.outcome === 'APPROVED' ? 'profit' : 'loss'}>{a.risk.outcome}</Badge> ({a.risk.checks.length} checks)</summary>
            <ul className="checks">{a.risk.checks.map((c) => <li key={c.name} className={c.passed ? 'check-pass' : 'check-fail'}>{c.passed ? '✓' : '✗'} {c.name}{c.message ? ` — ${c.message}` : ''}</li>)}</ul>
          </details>
        )}
        <div className="approval-actions">
          <Button variant="primary" data-testid={`approve-${a.id}`} disabled={busy || left === 'expired'} onClick={() => onDecide(a, 'approve')}>Approve</Button>
          <input aria-label="reason" placeholder="reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} disabled={busy} />
          <Button data-testid={`reject-${a.id}`} disabled={busy} onClick={() => onDecide(a, 'reject', reason)}>Reject</Button>
        </div>
      </div>
    </Card>
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
  const phone = useMediaQuery(PHONE);
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

  const columns: ColumnDef<Approval, any>[] = [
    { id: 'when', header: 'When', accessorFn: (a) => a.decidedAt ?? a.createdAt, cell: (c) => <span className="num">{new Date(c.getValue()).toLocaleTimeString()}</span> },
    { accessorKey: 'status', header: 'Status', cell: (c) => <Badge tone={STATUS_TONE[c.getValue()] ?? 'neutral'}>{c.getValue()}</Badge> },
    { accessorKey: 'summary', header: 'Summary' },
    { id: 'by', header: 'By', accessorFn: (a) => a.decidedBy ?? '—' },
    { id: 'note', header: 'Note / result', accessorFn: note },
  ];

  if (error) return <Page title="Approvals"><p className="message message-loss">Approvals unavailable: {(error as Error).message}</p></Page>;
  const decided = (recent ?? []).filter((a) => a.status !== 'PENDING');
  return (
    <Page title="Approvals">
      <p className="muted">Agents propose; approving re-runs the policy and risk checks, then sends the order through the normal pipeline.</p>
      {message && <p data-testid="approval-message" className="message">{message}</p>}
      {(pending ?? []).length === 0 && <div data-testid="no-approvals"><EmptyState title="Nothing is waiting for approval." /></div>}
      {(pending ?? []).map((a) => <ApprovalCard key={a.id} a={a} now={now} busy={busy === a.id} onDecide={decide} />)}
      <Card title="Recently decided">
        {phone ? (
          decided.length === 0 ? <EmptyState title="Nothing decided yet" /> : (
            <div className="item-cards">
              {decided.map((a) => (
                <div key={a.id} className="item-card">
                  <div className="item-card-head">
                    <span className="num">{new Date(a.decidedAt ?? a.createdAt).toLocaleTimeString()}</span>
                    <Badge tone={STATUS_TONE[a.status] ?? 'neutral'}>{a.status}</Badge>
                  </div>
                  <div>{a.summary}</div>
                  <div className="muted text-sm">{a.decidedBy ?? '—'} · {note(a)}</div>
                </div>
              ))}
            </div>
          )
        ) : (
          <DataTable data-testid="decided-approvals" columns={columns} data={decided} empty="Nothing decided yet" getRowId={(a) => a.id} />
        )}
      </Card>
    </Page>
  );
}
