import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { KillSwitch, RiskDashboard } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { Badge, Button, Card, Dialog, Field, Page, Stat, signed, tone } from '../ui';
import '../styles/trading.css';

const rupees = (paise: number) => `₹${formatPaise(paise)}`;
const pnl = (paise: number) => <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;

export function Risk() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['risk'], queryFn: () => request<RiskDashboard>('/risk'), refetchInterval: 4000 });
  const { data: ks } = useQuery({ queryKey: ['kill-switch'], queryFn: () => request<KillSwitch>('/risk/kill-switch'), refetchInterval: 4000 });
  const [confirm, setConfirm] = useState('');
  const [confirmCancelAll, setConfirmCancelAll] = useState(false);

  async function act(action: string, confirmation?: string) {
    await request('/risk/kill-switch', { method: 'POST', idempotent: true, body: { action, confirmation } });
    qc.invalidateQueries({ queryKey: ['kill-switch'] });
  }
  async function rearm() {
    await request('/risk/kill-switch', { method: 'DELETE', idempotent: true });
    qc.invalidateQueries({ queryKey: ['kill-switch'] });
  }

  return (
    <Page title="Risk" actions={<Link to="/risk/policies">Approval policies (PRD 49) →</Link>}>
      <Card
        title={<>Kill switch {ks?.stopNewOrders ? `(STOPPING — ${ks.reason ?? ''})` : '(armed)'}</>}
        actions={ks && (ks.stopNewOrders ? <Badge tone="loss">■ STOPPING NEW ORDERS</Badge> : <Badge tone="profit">ARMED</Badge>)}
        className="risk-kill"
      >
        <div className="risk-kill-actions">
          <Button variant="danger" onClick={() => act('STOP_NEW_ORDERS')}>Stop new orders</Button>
          <Button variant="danger" onClick={() => setConfirmCancelAll(true)}>Cancel all open</Button>
          <div className="risk-close-all">
            <Field label="Type CLOSE ALL to close every position">
              <input aria-label="confirm" placeholder="type CLOSE ALL" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </Field>
            <Button variant="danger" disabled={confirm !== 'CLOSE ALL'} onClick={() => act('CLOSE_ALL_POSITIONS', confirm)}>Close all positions</Button>
          </div>
          <Button onClick={rearm}>Re-arm</Button>
        </div>
      </Card>
      {data && (
        <Card title="Today">
          <div className="stat-grid">
            <Stat label="Realized" value={pnl(data.realizedPnl.paise)} />
            <Stat label="Unrealized" value={pnl(data.unrealizedPnl.paise)} />
            <Stat label="Net" value={pnl(data.netPnl.paise)} />
            <Stat label="Daily loss limit" value={rupees(data.dailyLossLimit.paise)} />
            <Stat label="Open positions" value={`${data.openPositions}/${data.maxOpenPositions}`} />
            <Stat label="Trades today" value={`${data.tradesToday}/${data.maxTradesPerDay}`} />
            <Stat label="Margin used" value={`${data.marginUsedPct}%`} />
          </div>
          <dl className="kv risk-streak">
            <dt>Loss streak</dt>
            <dd data-testid="risk-allowance">{data.lossStreakMode === 'ALLOWANCE'
              ? (data.allowance != null ? `allowance ${data.allowanceUsed}/${data.allowance} (${data.allowanceReason})` : `allowance mode, not triggered (${data.consecutiveLosses} in a row)`)
              : `${data.consecutiveLosses} in a row (block)`}</dd>
          </dl>
        </Card>
      )}
      <Dialog
        open={confirmCancelAll}
        title="Cancel all open orders?"
        onClose={() => setConfirmCancelAll(false)}
        actions={<>
          <Button onClick={() => setConfirmCancelAll(false)}>Keep them</Button>
          <Button variant="danger" onClick={() => { setConfirmCancelAll(false); void act('CANCEL_ALL_OPEN'); }}>Cancel all open</Button>
        </>}
      >
        Every open order is cancelled.
      </Dialog>
    </Page>
  );
}
