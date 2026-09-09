import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Deployment, Strategy } from '../api/types';

export function Strategies() {
  const { data } = useQuery({ queryKey: ['strategies'], queryFn: () => request<Strategy[]>('/strategies'), refetchInterval: 10000 });
  const { data: deployments } = useQuery({ queryKey: ['deployments'], queryFn: () => request<Deployment[]>('/deployments'), refetchInterval: 10000 });
  return (
    <div>
      <h1>Strategies</h1>
      <p><Link to="/lab">Open the Lab to create or edit a strategy</Link></p>
      <table data-testid="strategies-table">
        <thead><tr><th>Strategy</th><th>Family</th><th>Latest</th><th>Status</th><th>Deployments</th></tr></thead>
        <tbody>
          {(data ?? []).map((s) => {
            const deps = (deployments ?? []).filter((d) => d.strategyId === s.id);
            return (
              <tr key={s.id}>
                <td><Link to={`/strategies/${s.id}`}>{s.slug}</Link></td><td>{s.family}</td><td>v{s.latestVersion}</td><td>{s.latestStatus ?? '—'}</td>
                <td>{deps.length ? deps.map((d) => `${d.mode}${d.enabled ? '' : ' (paused)'}`).join(', ') : '—'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
