import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Health } from '../api/types';

interface Latency { name: string; op?: string; p50Ms: number; p95Ms: number; p99Ms: number; count: number; }
interface Issue { id: string; kind: string; severity: string; detail: string; }

export function System() {
  const qc = useQueryClient();
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health'), refetchInterval: 5000 });
  const { data: latency } = useQuery({ queryKey: ['latency'], queryFn: () => request<Latency[]>('/server/latency'), refetchInterval: 5000 });
  const { data: issues } = useQuery({ queryKey: ['issues'], queryFn: () => request<Issue[]>('/execution/reconciliation-issues'), refetchInterval: 5000 });

  async function resolve(id: string) {
    await request(`/execution/reconciliation-issues/${id}/resolve`, { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['issues'] });
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
      <table><tbody>{checks.map(([k, v]) => <tr key={k}><td>{k}</td><td>{v}</td></tr>)}</tbody></table>
      <h3>Latency (ms)</h3>
      <table><thead><tr><th>Timer</th><th>p50</th><th>p95</th><th>p99</th><th>count</th></tr></thead>
        <tbody>{(latency ?? []).map((l) => <tr key={l.name + (l.op ?? '')}><td>{l.name}{l.op ? `:${l.op}` : ''}</td><td>{l.p50Ms}</td><td>{l.p95Ms}</td><td>{l.p99Ms}</td><td>{l.count}</td></tr>)}</tbody>
      </table>
      <h3>Reconciliation issues</h3>
      <table><tbody>{(issues ?? []).map((i) => <tr key={i.id}><td>{i.severity}</td><td>{i.kind}</td><td>{i.detail}</td><td><button onClick={() => resolve(i.id)}>Resolve</button></td></tr>)}</tbody></table>
    </div>
  );
}
