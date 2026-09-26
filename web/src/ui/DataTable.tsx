import { ReactNode, useState } from 'react';
import {
  ColumnDef, Row, RowData, SortingState, flexRender, getCoreRowModel, getSortedRowModel, useReactTable,
} from '@tanstack/react-table';
import { toNumber } from './format';
import { EmptyState } from './EmptyState';
import { Skeleton } from './Skeleton';

declare module '@tanstack/react-table' {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData extends RowData, TValue> {
    /** a number column: tabular figures, right-aligned, sorted by value (numeric strings included) */
    numeric?: boolean;
  }
}

/** Sorts numbers and numeric strings by value; missing or unparsable values go after the numbers (before them when descending). */
export function numericSort<T>(a: Row<T>, b: Row<T>, columnId: string): number {
  const x = toNumber(a.getValue(columnId));
  const y = toNumber(b.getValue(columnId));
  if (x === null && y === null) return 0;
  if (x === null) return 1;
  if (y === null) return -1;
  return x - y;
}

interface DataTableProps<T> {
  columns: ColumnDef<T, any>[];
  data: T[] | undefined;
  /** shows skeleton rows instead of the empty state */
  loading?: boolean;
  /** what an empty table says */
  empty?: ReactNode;
  initialSort?: SortingState;
  getRowId?: (row: T, index: number) => string;
  'data-testid'?: string;
}

/** A table with a sticky header, numeric columns, click-to-sort headers, and horizontal scroll inside the table only. */
export function DataTable<T>({ columns, data, loading, empty = 'Nothing here yet', initialSort = [], getRowId, ...rest }: DataTableProps<T>) {
  const [sorting, setSorting] = useState<SortingState>(initialSort);
  const table = useReactTable({
    data: data ?? [],
    columns: columns.map((c) => (c.meta?.numeric && !c.sortingFn ? { ...c, sortingFn: numericSort } : c)),
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getRowId,
  });
  const rows = table.getRowModel().rows;
  const width = table.getVisibleLeafColumns().length;

  return (
    <div className="dt-scroll" aria-busy={loading || undefined}>
      <table className="dt" data-testid={rest['data-testid']}>
        <thead>
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => {
                const numeric = h.column.columnDef.meta?.numeric;
                const sorted = h.column.getIsSorted();
                const label = h.isPlaceholder ? null : flexRender(h.column.columnDef.header, h.getContext());
                return (
                  <th
                    key={h.id}
                    className={numeric ? 'num' : undefined}
                    aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : undefined}
                  >
                    {h.column.getCanSort() ? (
                      <button type="button" className="dt-sort" onClick={h.column.getToggleSortingHandler()}>
                        {label}
                        <span className="dt-sort-icon" aria-hidden="true">{sorted === 'asc' ? '▲' : sorted === 'desc' ? '▼' : '↕'}</span>
                      </button>
                    ) : label}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {loading && rows.length === 0 ? (
            [0, 1, 2].map((i) => <tr key={i}><td colSpan={width}><Skeleton /></td></tr>)
          ) : rows.length === 0 ? (
            <tr><td colSpan={width} className="dt-empty"><EmptyState title={empty} /></td></tr>
          ) : rows.map((r) => (
            <tr key={r.id}>
              {r.getVisibleCells().map((c) => (
                <td key={c.id} className={c.column.columnDef.meta?.numeric ? 'num' : undefined}>
                  {flexRender(c.column.columnDef.cell, c.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
