import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { request } from '../api/client';
import { TradeReview } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { formatR } from '../lib/today';

export function ReviewDetail() {
  const { id } = useParams();
  const { data: r } = useQuery({ queryKey: ['review', id], queryFn: () => request<TradeReview>(`/reviews/${id}`) });
  if (!r) return <p>Loading…</p>;
  return (
    <div data-testid="review-detail">
      <h1>Post-trade review</h1>
      <table>
        <tbody>
          <tr><td>Strategy</td><td>{r.strategyId ? <Link to={`/strategies/${r.strategyId}`}>{r.strategyId.slice(0, 8)}</Link> : 'MANUAL'}</td></tr>
          <tr><td>Outcome</td><td data-testid="review-outcome">{formatR(r.outcomeR)} ({formatPaise(r.netPnl.paise)} net)</td></tr>
          <tr><td>Expected setup</td><td>{r.expectedSetupValid == null ? '—' : r.expectedSetupValid ? 'VALID' : 'INVALID'}</td></tr>
          <tr><td>Entry slippage</td><td>{r.entrySlippageBps != null ? `${r.entrySlippageBps} bps` : '—'}</td></tr>
          <tr><td>Exit slippage</td><td>{r.exitSlippageBps != null ? `${r.exitSlippageBps} bps` : '—'}</td></tr>
          <tr><td>Close reason</td><td>{r.closeReason ?? '—'}</td></tr>
          <tr><td>Cause</td><td data-testid="review-cause">{r.cause ? `${r.cause.cause}${r.cause.complete ? '' : ' (provisional)'}` : '—'}
            {r.cause?.jevCause ? ` · Jev: ${r.cause.jevCause}` : ''}</td></tr>
          <tr><td>Entry timing</td><td>{r.cause?.entryTiming ?? '—'}{r.cause?.jevTiming ? ` · Jev: ${r.cause.jevTiming}` : ''}</td></tr>
          <tr><td>MFE / MAE</td><td>{r.cause ? `${formatR(r.cause.mfeR)} / ${formatR(r.cause.maeR)}` : '—'}</td></tr>
          <tr><td>Rule adherence</td><td>{r.ruleAdherencePct != null ? `${r.ruleAdherencePct}%` : '—'}</td></tr>
          <tr><td>Context</td><td>{Object.entries(r.context).map(([k, v]) => `${k}: ${v}`).join(', ')}</td></tr>
          <tr><td>Trade</td><td>{r.side} {r.quantity} @ {r.entryPrice} → {r.exitPrice} ({new Date(r.openedAt).toLocaleString()} – {new Date(r.closedAt).toLocaleTimeString()})</td></tr>
          <tr><td>Notes</td><td>{r.notes}</td></tr>
        </tbody>
      </table>
    </div>
  );
}

export function Reviews() {
  const { data } = useQuery({ queryKey: ['reviews'], queryFn: () => request<TradeReview[]>('/reviews'), refetchInterval: 5000 });
  return (
    <div>
      <h1>Reviews</h1>
      <table data-testid="reviews-table">
        <thead><tr><th>Closed</th><th>Strategy</th><th>Side</th><th>Qty</th><th>Net</th><th>R</th><th>Reason</th><th>Cause</th><th>Timing</th><th></th></tr></thead>
        <tbody>
          {(data ?? []).map((r) => (
            <tr key={r.id}><td>{new Date(r.closedAt).toLocaleString()}</td><td>{r.strategyId ? r.strategyId.slice(0, 8) : 'MANUAL'}</td><td>{r.side}</td><td>{r.quantity}</td>
              <td>{formatPaise(r.netPnl.paise)}</td><td>{formatR(r.outcomeR)}</td><td>{r.closeReason ?? '—'}</td><td>{r.cause?.cause ?? '—'}</td>
              <td>{r.cause?.entryTiming ?? '—'}</td><td><Link to={`/reviews/${r.id}`}>Review</Link></td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
