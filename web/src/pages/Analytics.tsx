import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { PnlReport } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { formatR } from '../lib/today';
import { LossInvestigation } from '../components/LossInvestigation';

const GROUPS = ['strategy', 'version', 'instrument', 'weekday', 'hour', 'regime', 'family', 'eventContext', 'newsBias', 'exitReason'];

export function Analytics() {
  const [groupBy, setGroupBy] = useState('strategy');
  const { data } = useQuery({ queryKey: ['pnl', groupBy], queryFn: () => request<PnlReport>(`/analytics/pnl?groupBy=${groupBy}`), refetchInterval: 10000 });
  return (
    <div>
      <h1>Analytics</h1>
      <div style={{ display: 'flex', gap: 8 }}>
        {GROUPS.map((g) => <button key={g} onClick={() => setGroupBy(g)} style={{ fontWeight: g === groupBy ? 700 : 400 }}>{g}</button>)}
      </div>
      {data && (
        <p>Last 30 days: {data.summary.roundTrips} round trips, gross {formatPaise(data.summary.grossPnl.paise)}, fees {formatPaise(data.summary.fees.paise)},
          net <b data-testid="pnl-net">{formatPaise(data.summary.netPnl.paise)}</b></p>
      )}
      <table data-testid="pnl-table">
        <thead><tr><th>{groupBy}</th><th>Trades</th><th>Win rate</th><th>Gross</th><th>Fees</th><th>Net</th><th>Avg R</th></tr></thead>
        <tbody>
          {(data?.buckets ?? []).map((b) => (
            <tr key={b.key}><td>{b.label}</td><td>{b.trades}</td><td>{Math.round(b.winRate * 100)}%</td><td>{formatPaise(b.grossPnl.paise)}</td>
              <td>{formatPaise(b.fees.paise)}</td><td>{formatPaise(b.netPnl.paise)}</td><td>{formatR(b.averageR)}</td></tr>
          ))}
        </tbody>
      </table>
      <LossInvestigation />
    </div>
  );
}
