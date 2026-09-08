import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { KillSwitch, RiskDashboard } from '../api/types';
import { formatPaise } from '../lib/sizing';

export function Risk() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['risk'], queryFn: () => request<RiskDashboard>('/risk'), refetchInterval: 4000 });
  const { data: ks } = useQuery({ queryKey: ['kill-switch'], queryFn: () => request<KillSwitch>('/risk/kill-switch'), refetchInterval: 4000 });
  const [confirm, setConfirm] = useState('');

  async function act(action: string, confirmation?: string) {
    await request('/risk/kill-switch', { method: 'POST', idempotent: true, body: { action, confirmation } });
    qc.invalidateQueries({ queryKey: ['kill-switch'] });
  }
  async function rearm() {
    await request('/risk/kill-switch', { method: 'DELETE', idempotent: true });
    qc.invalidateQueries({ queryKey: ['kill-switch'] });
  }

  return (
    <div>
      <h1>Risk</h1>
      {data && (
        <table>
          <tbody>
            <tr><td>Realized</td><td>{formatPaise(data.realizedPnl.paise)}</td></tr>
            <tr><td>Unrealized</td><td>{formatPaise(data.unrealizedPnl.paise)}</td></tr>
            <tr><td>Net</td><td>{formatPaise(data.netPnl.paise)}</td></tr>
            <tr><td>Daily loss limit</td><td>{formatPaise(data.dailyLossLimit.paise)}</td></tr>
            <tr><td>Open positions</td><td>{data.openPositions}/{data.maxOpenPositions}</td></tr>
            <tr><td>Trades today</td><td>{data.tradesToday}/{data.maxTradesPerDay}</td></tr>
            <tr><td>Margin used</td><td>{data.marginUsedPct}%</td></tr>
          </tbody>
        </table>
      )}
      <h3 style={{ marginTop: 16 }}>Kill switch {ks?.stopNewOrders ? `(STOPPING — ${ks.reason ?? ''})` : '(armed)'}</h3>
      <div style={{ display: 'flex', gap: 8 }}>
        <button onClick={() => act('STOP_NEW_ORDERS')}>Stop new orders</button>
        <button onClick={() => act('CANCEL_ALL_OPEN')}>Cancel all open</button>
        <span>
          <input aria-label="confirm" placeholder='type CLOSE ALL' value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          <button disabled={confirm !== 'CLOSE ALL'} onClick={() => act('CLOSE_ALL_POSITIONS', confirm)}>Close all positions</button>
        </span>
        <button onClick={rearm}>Re-arm</button>
      </div>
    </div>
  );
}
