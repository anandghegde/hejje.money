import { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ColumnDef } from '@tanstack/react-table';
import { Button, DataTable, Dialog, Field, Stat, Tabs, signed, toNumber, tone } from '../src/ui';

afterEach(cleanup);

describe('number formatting', () => {
  it('signs every non-zero number and groups the Indian way', () => {
    expect(signed(1250.5)).toBe('+1,250.50');
    expect(signed(-842.25)).toBe('−842.25');
    expect(signed(0)).toBe('0.00');
    expect(signed(-0.001)).toBe('0.00'); // rounds to zero: no sign
    expect(signed(123456.789, 1, ' %')).toBe('+1,23,456.8 %');
    expect(signed(null)).toBe('—');
    expect(signed(undefined)).toBe('—');
  });

  it('maps values to profit, loss or neutral', () => {
    expect(tone(3)).toBe('profit');
    expect(tone(-0.5)).toBe('loss');
    expect(tone(0)).toBe('neutral');
    expect(tone(null)).toBe('neutral');
  });

  it('reads numbers from API strings', () => {
    expect(toNumber('1495.00')).toBe(1495);
    expect(toNumber('₹1,23,456.50')).toBe(123456.5);
    expect(toNumber('−2.5')).toBe(-2.5);
    expect(toNumber('')).toBeNull();
    expect(toNumber('n/a')).toBeNull();
    expect(toNumber(undefined)).toBeNull();
  });

  it('Stat shows the delta with sign and tone, not colour alone', () => {
    render(<Stat label="Day P&L" value="₹10" delta={-12.5} data-testid="s" />);
    const delta = screen.getByTestId('s').querySelector('.stat-delta')!;
    expect(delta.textContent).toBe('−12.50');
    expect(delta.className).toContain('tone-loss');
  });
});

interface Row { symbol: string; ltp: string; }
const COLUMNS: ColumnDef<Row, any>[] = [
  { accessorKey: 'symbol', header: 'Symbol' },
  { accessorKey: 'ltp', header: 'LTP', meta: { numeric: true } },
];
const ROWS: Row[] = [{ symbol: 'A', ltp: '950.50' }, { symbol: 'B', ltp: '1500.00' }, { symbol: 'C', ltp: '87.10' }];
const column = (i: number) => screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[i].textContent);

describe('DataTable', () => {
  it('sorts numeric string columns by value, then descending', () => {
    render(<DataTable columns={COLUMNS} data={ROWS} data-testid="t" />);
    expect(column(0)).toEqual(['A', 'B', 'C']);
    fireEvent.click(screen.getByRole('button', { name: /LTP/ }));
    expect(column(1)).toEqual(['87.10', '950.50', '1500.00']);
    expect(screen.getByRole('columnheader', { name: /LTP/ }).getAttribute('aria-sort')).toBe('ascending');
    fireEvent.click(screen.getByRole('button', { name: /LTP/ }));
    expect(column(1)).toEqual(['1500.00', '950.50', '87.10']);
  });

  it('right-aligns numeric cells', () => {
    render(<DataTable columns={COLUMNS} data={ROWS} />);
    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell');
    expect(cells[0].className).not.toContain('num');
    expect(cells[1].className).toContain('num');
  });

  it('shows the empty state and the loading state', () => {
    const { rerender } = render(<DataTable columns={COLUMNS} data={[]} empty="No positions" />);
    expect(screen.getByText('No positions')).toBeTruthy();
    rerender(<DataTable columns={COLUMNS} data={undefined} loading />);
    expect(screen.queryByText('No positions')).toBeNull();
    expect(document.querySelectorAll('.skeleton').length).toBe(3);
  });
});

function DialogHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Delete</Button>
      <Dialog
        open={open}
        title="Delete it?"
        onClose={() => setOpen(false)}
        actions={<><Button onClick={() => setOpen(false)}>Cancel</Button><Button variant="danger">Delete for good</Button></>}
      >
        Gone for good.
      </Dialog>
    </>
  );
}

describe('Dialog', () => {
  it('moves focus in, traps Tab, closes on Escape and returns focus to the trigger', () => {
    render(<DialogHarness />);
    const trigger = screen.getByRole('button', { name: 'Delete' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Delete it?' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    const confirm = screen.getByRole('button', { name: 'Delete for good' });
    expect(document.activeElement).toBe(cancel);
    confirm.focus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(cancel);
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(confirm);
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
});

describe('Tabs and Field', () => {
  it('arrow keys move the selection', () => {
    function Harness() {
      const [v, setV] = useState('a');
      return <Tabs aria-label="t" value={v} onChange={setV} tabs={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }]} />;
    }
    render(<Harness />);
    fireEvent.keyDown(screen.getByRole('tab', { name: 'A' }), { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'B' }).getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'B' }), { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'A' }).getAttribute('aria-selected')).toBe('true');
  });

  it('labels the control and describes it with the hint and error', () => {
    render(<Field label="Quantity" hint="Whole shares" error="Must be at least 1"><input /></Field>);
    const input = screen.getByLabelText('Quantity');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const described = input.getAttribute('aria-describedby')!.split(' ').map((id) => document.getElementById(id)?.textContent);
    expect(described).toEqual(['Whole shares', 'Must be at least 1']);
  });
});
