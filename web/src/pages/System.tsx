import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Health } from '../api/types';
import { BrokerAccountsView, canFailover, executorLine, ExecutorStatus } from '../lib/executor';
import { Badge, Button, Card, Dialog, Page } from '../ui';
import '../styles/system.css';

const STATUS_OK = ['UP', 'HEALTHY', 'OK', 'SKIPPED', 'ENABLED'];

interface Latency { name: string; op?: string; broker?: string; p50Ms: number; p95Ms: number; p99Ms: number; count: number; }
interface Issue { id: string; kind: string; severity: string; detail: string; broker?: string; }
interface JevStatus {
  enabled: boolean; keyPresent: boolean; model: string; circuit: string; lastError?: string; budgetExceeded: boolean;
  today: { calls: number; ok: number; cached: number; failed: number; timeouts: number; costPaise: number; p50Ms?: number; p90Ms?: number };
}

export function System() {
  const qc = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [confirmFailover, setConfirmFailover] = useState(false);
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
    setConfirmFailover(false);
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
    <Page title="Server">
      <Card title="Health">
        <div className="stack">
          <p>Execution <Badge tone={health?.executionEnabled ? 'profit' : 'loss'}>{health?.executionEnabled ? 'enabled' : 'disabled'}</Badge> {health?.reasons?.length ? `— ${health.reasons.join('; ')}` : ''}</p>
          {executor && (
            <p data-testid="executor-role" className="cluster">
              Executor: {executorLine(executor)}
              {canFailover(executor) && <Button variant="danger" size="sm" onClick={() => setConfirmFailover(true)}>Fail over to the standby</Button>}
            </p>
          )}
          {message && <p className="message">{message}</p>}
          <dl className="kv">{checks.map(([k, v]) => (
            <div key={k} className="kv-row"><dt>{k}</dt><dd><Badge tone={STATUS_OK.includes(v) ? 'profit' : v === 'DEGRADED' || v === 'STALE' ? 'warning' : 'loss'}>{v}</Badge></dd></div>
          ))}</dl>
        </div>
      </Card>
      {jev && (
        <Card title="Jev">
          <dl className="kv" data-testid="jev-status">
            <dt>Status</dt><dd>{!jev.enabled ? 'disabled' : !jev.keyPresent ? 'enabled, no API key' : `enabled, model ${jev.model}, circuit ${jev.circuit}`}</dd>
            <dt>Today</dt><dd>{jev.today.calls} calls ({jev.today.ok} ok, {jev.today.cached} cached, {jev.today.timeouts} timeouts, {jev.today.failed} failed)</dd>
            <dt>Latency</dt><dd>{jev.today.p50Ms != null ? `p50 ${jev.today.p50Ms} ms, p90 ${jev.today.p90Ms} ms` : '—'}</dd>
            <dt>Cost today</dt><dd>₹{(jev.today.costPaise / 100).toFixed(2)}{jev.budgetExceeded ? ' (cap reached)' : ''}</dd>
            {jev.lastError && <><dt>Last error</dt><dd className="tone-loss">{jev.lastError}</dd></>}
          </dl>
        </Card>
      )}
      <Card title="Latency (ms)">
        <div className="table-scroll">
          <table><thead><tr><th>Timer</th><th>Broker</th><th className="num">p50</th><th className="num">p95</th><th className="num">p99</th><th className="num">count</th></tr></thead>
            <tbody>{(latency ?? []).map((l) => <tr key={l.name + (l.op ?? '') + (l.broker ?? '')}><td>{l.name}{l.op ? `:${l.op}` : ''}</td><td>{l.broker ?? ''}</td><td className="num">{l.p50Ms}</td><td className="num">{l.p95Ms}</td><td className="num">{l.p99Ms}</td><td className="num">{l.count}</td></tr>)}</tbody>
          </table>
        </div>
      </Card>
      <Card title="Reconciliation issues">
        {(issues ?? []).length === 0 ? <p className="muted">No open issues.</p> : (
          <div className="table-scroll">
            <table><tbody>{(issues ?? []).map((i) => <tr key={i.id}><td><Badge tone={i.severity === 'CRITICAL' || i.severity === 'HIGH' ? 'loss' : 'warning'}>{i.severity}</Badge></td><td>{i.broker ?? ''}</td><td>{i.kind}</td><td>{i.detail}</td><td><Button size="sm" onClick={() => resolve(i.id)}>Resolve</Button></td></tr>)}</tbody></table>
          </div>
        )}
      </Card>
      {accounts && (
        <Card title="Broker accounts">
          <div className="stack">
            <p>This server runs the <b>{accounts.adapter}</b> adapter; orders go only to the active account.</p>
            <div className="table-scroll">
              <table><tbody>{accounts.accounts.map((a) => (
                <tr key={a.id}><td>{a.broker}</td><td className="mono">{a.accountId}</td><td>{a.active ? <Badge tone="profit">active</Badge> : ''}</td>
                  <td>{!a.active && <Button size="sm" onClick={() => activate(a.id)}>Make active</Button>}</td></tr>
              ))}</tbody></table>
            </div>
          </div>
        </Card>
      )}
      <Dialog
        open={confirmFailover}
        title="Fail over to the standby?"
        onClose={() => setConfirmFailover(false)}
        actions={<>
          <Button onClick={() => setConfirmFailover(false)}>Cancel</Button>
          <Button variant="danger" onClick={failover}>Release the lease</Button>
        </>}
      >
        Release the executor lease on this instance? The standby takes over and this instance stops sending orders.
      </Dialog>
    </Page>
  );
}
