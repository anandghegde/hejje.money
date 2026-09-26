import { useState } from 'react';
import { ColumnDef } from '@tanstack/react-table';
import {
  Badge, BadgeTone, Button, Card, DataTable, Dialog, EmptyState, Field, Page, Skeleton, Stat, Tabs, Toast, signed, tone,
} from '../ui';
import '../styles/design-page.css';

/** The living style page (`/design`): every token group and component, side by side in the light and dark theme. */

interface Row { symbol: string; qty: number; ltp: string; pnl: number; }
const ROWS: Row[] = [
  { symbol: 'NSE:INFY', qty: 10, ltp: '1495.00', pnl: 1250.5 },
  { symbol: 'NSE:TCS', qty: -5, ltp: '3890.40', pnl: -842.25 },
  { symbol: 'NSE:SBIN', qty: 40, ltp: '812.15', pnl: 0 },
];
const COLUMNS: ColumnDef<Row, any>[] = [
  { accessorKey: 'symbol', header: 'Symbol' },
  { accessorKey: 'qty', header: 'Qty', meta: { numeric: true } },
  { accessorKey: 'ltp', header: 'LTP', meta: { numeric: true } },
  {
    accessorKey: 'pnl', header: 'P&L', meta: { numeric: true },
    cell: (c) => <span className={`tone-${tone(c.getValue())}`}>{signed(c.getValue())}</span>,
  },
];

const SWATCHES = ['bg', 'surface', 'surface-2', 'border', 'text', 'text-secondary', 'text-muted', 'accent', 'profit', 'loss',
  'warning', 'info', 'mode-live', 'mode-paper', 'mode-sim'];
const BADGES: BadgeTone[] = ['neutral', 'profit', 'loss', 'warning', 'info', 'live', 'paper', 'sim'];

function Gallery({ theme }: { theme: 'light' | 'dark' }) {
  const [tab, setTab] = useState('positions');
  const [dialog, setDialog] = useState(false);
  return (
    <div className="design-theme" data-theme={theme} data-testid={`design-${theme}`}>
      <h2>{theme === 'light' ? 'Light' : 'Dark'}</h2>

      <Card title="Colour tokens">
        <div className="design-swatches">
          {SWATCHES.map((s) => (
            <div key={s} className="design-swatch">
              <span className={`design-chip design-chip-${s}`} />
              <code>--{s}</code>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Type">
        <div className="design-type">
          <span className="design-2xl">Page title 2xl</span>
          <span className="design-xl">Section xl</span>
          <span className="design-lg">Card title lg</span>
          <span>Body md</span>
          <span className="design-sm">Table and hint sm</span>
          <span className="design-xs">Caption xs</span>
          <span className="num">1,23,456.78 · <span className="profit">+2.50 %</span> · <span className="loss">−1.25 %</span></span>
        </div>
      </Card>

      <Card title="Buttons and badges">
        <div className="design-row">
          <Button variant="primary">Primary</Button>
          <Button>Secondary</Button>
          <Button variant="danger">Danger</Button>
          <Button disabled>Disabled</Button>
          <Button variant="primary" size="sm">Small</Button>
        </div>
        <div className="design-row">
          {BADGES.map((b) => <Badge key={b} tone={b}>{b.toUpperCase()}</Badge>)}
        </div>
      </Card>

      <Card title="Stats">
        <div className="design-stats">
          <Stat label="Day P&L" value="₹1,250.50" delta={1250.5} />
          <Stat label="Open risk" value="₹842.25" delta={-842.25} />
          <Stat label="Trades" value="3" delta={0} />
        </div>
      </Card>

      <Card title="Fields">
        <div className="design-fields">
          <Field label="Symbol" hint="Exchange and symbol, e.g. NSE:INFY"><input defaultValue="NSE:INFY" /></Field>
          <Field label="Quantity" error="Quantity must be at least 1"><input type="number" defaultValue={0} /></Field>
          <Field label="Product"><select defaultValue="MIS"><option>MIS</option><option>CNC</option></select></Field>
        </div>
      </Card>

      <Card title="Tabs and table" actions={<Button size="sm" onClick={() => setDialog(true)}>Open dialog</Button>}>
        <Tabs
          aria-label={`Example tabs (${theme})`}
          value={tab}
          onChange={setTab}
          tabs={[{ id: 'positions', label: 'Positions' }, { id: 'loading', label: 'Loading' }, { id: 'empty', label: 'Empty' }]}
        />
        <div className="design-tab-panel">
          {tab === 'positions' && <DataTable columns={COLUMNS} data={ROWS} />}
          {tab === 'loading' && <DataTable columns={COLUMNS} data={undefined} loading />}
          {tab === 'empty' && <DataTable columns={COLUMNS} data={[]} empty="No open positions" />}
        </div>
      </Card>

      <Card title="Feedback">
        <Toast tone="info" title="Order filled" body="BUY 10 NSE:INFY at 1495.00" />
        <Toast tone="warning" title="Drift warning" body="ORB-15 slipped below its baseline" />
        <Toast tone="loss" title="Stop hit" body="NSE:TCS closed at a loss" />
        <EmptyState title="No signals yet" action={<Button size="sm">Refresh</Button>}>Signals appear once the session opens.</EmptyState>
        <Skeleton lines={3} />
      </Card>

      <Dialog
        open={dialog}
        title="Cancel all open orders?"
        onClose={() => setDialog(false)}
        actions={<>
          <Button onClick={() => setDialog(false)}>Keep them</Button>
          <Button variant="danger" onClick={() => setDialog(false)}>Cancel orders</Button>
        </>}
      >
        This is an example: nothing is sent.
      </Dialog>
    </div>
  );
}

export function Design() {
  return (
    <Page title="Design system">
      <p>Tokens live in <code>web/src/styles/tokens.css</code>, components in <code>web/src/ui</code>. Both themes are shown whatever the current setting.</p>
      <div className="design-grid">
        <Gallery theme="light" />
        <Gallery theme="dark" />
      </div>
    </Page>
  );
}
