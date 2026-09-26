import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiBaseUrl, request } from '../api/client';
import { BrokerStatus } from '../api/types';
import { Badge, Button, Card, Page } from '../ui';

const STATE_TONE = { CONNECTED: 'profit', EXPIRED: 'warning', DISCONNECTED: 'neutral', ERROR: 'loss' } as const;

export function Broker() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['broker'], queryFn: () => request<BrokerStatus>('/broker/status'), refetchInterval: 5000 });

  async function connect() {
    const { loginUrl } = await request<{ loginUrl: string }>('/broker/login-url');
    window.open(loginUrl, '_blank');
  }
  async function logout() {
    await request('/broker/logout', { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['broker'] });
  }

  return (
    <Page title="Broker">
      <Card>
        <div className="stack">
          {data && (
            <dl className="kv">
              <dt>Broker</dt><dd>{data.broker}</dd>
              <dt>State</dt><dd><Badge tone={STATE_TONE[data.state] ?? 'neutral'} data-testid="broker-state">{data.state}</Badge></dd>
              <dt>User</dt><dd>{data.brokerUserId ?? '—'}</dd>
              <dt>Live trading</dt><dd>{data.liveTradingEnabled ? 'enabled' : 'disabled'}</dd>
              <dt>Detail</dt><dd>{data.detail}</dd>
            </dl>
          )}
          <div className="cluster">
            <Button variant="primary" onClick={connect}>Connect</Button>
            <Button onClick={logout}>Log out broker</Button>
          </div>
          <p className="muted text-sm">Base API: {apiBaseUrl()}</p>
        </div>
      </Card>
    </Page>
  );
}
