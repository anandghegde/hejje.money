import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Health } from '../api/types';
import { BrokerAccountsView, canFailover, executorLine, ExecutorStatus } from '../lib/executor';

interface Latency { name: string; op?: string; broker?: string; p50Ms: number; p95Ms: number; p99Ms: number; count: number; }
interface Issue { id: string; kind: string; severity: string; detail: string; broker?: string; }
interface JevStatus {
  enabled: boolean; keyPresent: boolean; model: string; circuit: string; lastError?: string; budgetExceeded: boolean;
  today: { calls: number; ok: number; cached: number; failed: number; timeouts: number; costPaise: number; p50Ms?: number; p90Ms?: number };
}

export function System() {
  const qc = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health'), refetchInterval: 5000 });
  const { data: latency } = useQuery({ queryKey: ['latency'], queryFn: () => request<Latency[]>('/server/latency'), refetchInterval: 5000 });
  const { data: issues } = useQuery({ queryKey: ['issues'], queryFn: () => request<Issue[]>('/execution/reconciliation-issues'), refetchInterval: 5000 });
  const { data: executor } = useQuery({ queryKey: ['executor'], queryFn: () => request<ExecutorStatus>('/server/executor'), refetchInterval: 5000 });
  const { data: accounts } = useQuery({ queryKey: ['broker-accounts'], queryFn: () => request<BrokerAccountsView>('/broker/accounts') });
  const { data: jev } = useQuery({ queryKey: ['jev-status'], queryFn: () => request<JevStatus>('/jev/status'), refetchInterval: 15000 });

  async function resolve(id: string) {
    await request(`/execution/reconciliation-issues/${id}/resolve`, { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['issues'] });
  }

  async function failover() {
    if (!window.confirm('Release the executor lease on this instance? The standby takes over and this instance stops sending orders.')) return;
    try {
      const r = await request<{ message: string }>('/server/failover', { method: 'POST', body: { confirmation: 'FAILOVER' } });
      setMessage(r.message);
    } catch (e) {
      setMessage((e as Error).message);
    }
    qc.invalidateQueries({ queryKey: ['executor'] });
  }

  async function activate(id: string) {
    const r = await request<{ note?: string }>(`/broker/accounts/${id}/activate`, { method: 'POST' });
    setMessage(r.note ?? 'Account activated');
    qc.invalidateQueries({ queryKey: ['broker-accounts'] });
  }

  const checks = health ? [
    ['Execution server', health.status], ['Static IP', health.staticIp.status], ['Broker', health.broker.status],
    ['Market data', health.marketData.status], ['Database', health.database.status], ['Clock sync', health.clockSync.status],
    ['Risk engine', health.riskEngine.status], ['Order queue', health.orderQueue.status],
  ] : [];

  return (
    <div>
      <h1>Server</h1>
      <p>Execution {health?.executionEnabled ? 'enabled' : 'disabled'} {health?.reasons?.length ? `— ${health.reasons.join('; ')}` : ''}</p>
      {executor && (
        <p data-testid="executor-role">
          Executor: {executorLine(executor)}{' '}
          {canFailover(executor) && <button onClick={failover}>Fail over to the standby</button>}
        </p>
      )}
      {message && <p>{message}</p>}
      <table><tbody>{checks.map(([k, v]) => <tr key={k}><td>{k}</td><td>{v}</td></tr>)}</tbody></table>
      {jev && (
        <>
          <h3>Jev</h3>
          <table data-testid="jev-status"><tbody>
            <tr><td>Status</td><td>{!jev.enabled ? 'disabled' : !jev.keyPresent ? 'enabled, no API key' : `enabled, model ${jev.model}, circuit ${jev.circuit}`}</td></tr>
            <tr><td>Today</td><td>{jev.today.calls} calls ({jev.today.ok} ok, {jev.today.cached} cached, {jev.today.timeouts} timeouts, {jev.today.failed} failed)</td></tr>
            <tr><td>Latency</td><td>{jev.today.p50Ms != null ? `p50 ${jev.today.p50Ms} ms, p90 ${jev.today.p90Ms} ms` : '—'}</td></tr>
            <tr><td>Cost today</td><td>₹{(jev.today.costPaise / 100).toFixed(2)}{jev.budgetExceeded ? ' (cap reached)' : ''}</td></tr>
            {jev.lastError && <tr><td>Last error</td><td>{jev.lastError}</td></tr>}
          </tbody></table>
        </>
      )}
      <h3>Latency (ms)</h3>
      <table><thead><tr><th>Timer</th><th>Broker</th><th>p50</th><th>p95</th><th>p99</th><th>count</th></tr></thead>
        <tbody>{(latency ?? []).map((l) => <tr key={l.name + (l.op ?? '') + (l.broker ?? '')}><td>{l.name}{l.op ? `:${l.op}` : ''}</td><td>{l.broker ?? ''}</td><td>{l.p50Ms}</td><td>{l.p95Ms}</td><td>{l.p99Ms}</td><td>{l.count}</td></tr>)}</tbody>
      </table>
      <h3>Reconciliation issues</h3>
      <table><tbody>{(issues ?? []).map((i) => <tr key={i.id}><td>{i.severity}</td><td>{i.broker ?? ''}</td><td>{i.kind}</td><td>{i.detail}</td><td><button onClick={() => resolve(i.id)}>Resolve</button></td></tr>)}</tbody></table>
      {accounts && (
        <>
          <h3>Broker accounts</h3>
          <p>This server runs the <b>{accounts.adapter}</b> adapter; orders go only to the active account.</p>
          <table><tbody>{accounts.accounts.map((a) => (
            <tr key={a.id}><td>{a.broker}</td><td>{a.accountId}</td><td>{a.active ? 'active' : ''}</td>
              <td>{!a.active && <button onClick={() => activate(a.id)}>Make active</button>}</td></tr>
          ))}</tbody></table>
        </>
      )}
    </div>
  );
}
