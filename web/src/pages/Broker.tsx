import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiBaseUrl, request } from '../api/client';
import { BrokerStatus } from '../api/types';

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
    <div>
      <h1>Broker</h1>
      <p>Base API: {apiBaseUrl()}</p>
      {data && (
        <table>
          <tbody>
            <tr><td>Broker</td><td>{data.broker}</td></tr>
            <tr><td>State</td><td data-testid="broker-state">{data.state}</td></tr>
            <tr><td>User</td><td>{data.brokerUserId ?? '—'}</td></tr>
            <tr><td>Live trading</td><td>{data.liveTradingEnabled ? 'enabled' : 'disabled'}</td></tr>
            <tr><td>Detail</td><td>{data.detail}</td></tr>
          </tbody>
        </table>
      )}
      <div style={{ marginTop: 16 }}>
        <button onClick={connect}>Connect</button>{' '}
        <button onClick={logout}>Log out broker</button>
      </div>
    </div>
  );
}
