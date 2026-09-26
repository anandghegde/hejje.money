import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { request } from '../api/client';
import { PnlBucket, PnlReport } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { formatR } from '../lib/today';
import { LossInvestigation } from '../components/LossInvestigation';
import { CalibrationPanel } from '../components/CalibrationPanel';
import { Card, DataTable, Page, signed, tone } from '../ui';
import '../styles/research.css';

const GROUPS = ['strategy', 'version', 'instrument', 'weekday', 'hour', 'regime', 'family', 'eventContext', 'newsBias', 'exitReason'];

const pnl = (paise: number) => <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;

export function Analytics() {
  const [groupBy, setGroupBy] = useState('strategy');
  const { data, isLoading } = useQuery({ queryKey: ['pnl', groupBy], queryFn: () => request<PnlReport>(`/analytics/pnl?groupBy=${groupBy}`), refetchInterval: 10000 });
  const columns: ColumnDef<PnlBucket, any>[] = [
    { accessorKey: 'label', header: groupBy },
    { accessorKey: 'trades', header: 'Trades', meta: { numeric: true } },
    { accessorKey: 'winRate', header: 'Win rate', meta: { numeric: true }, cell: (c) => `${Math.round(c.getValue() * 100)}%` },
    { id: 'gross', header: 'Gross', accessorFn: (b) => b.grossPnl.paise, meta: { numeric: true }, cell: (c) => pnl(c.getValue()) },
    { id: 'fees', header: 'Fees', accessorFn: (b) => b.fees.paise, meta: { numeric: true }, cell: (c) => formatPaise(c.getValue()) },
    { id: 'net', header: 'Net', accessorFn: (b) => b.netPnl.paise, meta: { numeric: true }, cell: (c) => pnl(c.getValue()) },
    { accessorKey: 'averageR', header: 'Avg R', meta: { numeric: true }, cell: (c) => formatR(c.getValue()) },
  ];
  return (
    <Page title="Analytics">
      <Card title="P&L by group">
        <div className="stack">
          <div className="tabs" role="group" aria-label="group by">
            {GROUPS.map((g) => <button key={g} type="button" className="tab" aria-pressed={g === groupBy} onClick={() => setGroupBy(g)}>{g}</button>)}
          </div>
          {data && (
            <p>Last 30 days: {data.summary.roundTrips} round trips, gross {formatPaise(data.summary.grossPnl.paise)}, fees {formatPaise(data.summary.fees.paise)},
              net <b data-testid="pnl-net" className={`tone-${tone(data.summary.netPnl.paise)}`}>{formatPaise(data.summary.netPnl.paise)}</b></p>
          )}
          <DataTable data-testid="pnl-table" columns={columns} data={data?.buckets} loading={isLoading} empty="No closed round trips" getRowId={(b) => b.key} />
        </div>
      </Card>
      <LossInvestigation />
      <CalibrationPanel />
    </Page>
  );
}
