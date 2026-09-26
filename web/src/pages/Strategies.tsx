import { useQuery } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Deployment, Strategy } from '../api/types';
import { Badge, DataTable, Page } from '../ui';

export function Strategies() {
  const { data, isLoading } = useQuery({ queryKey: ['strategies'], queryFn: () => request<Strategy[]>('/strategies'), refetchInterval: 10000 });
  const { data: deployments } = useQuery({ queryKey: ['deployments'], queryFn: () => request<Deployment[]>('/deployments'), refetchInterval: 10000 });
  const columns: ColumnDef<Strategy, any>[] = [
    { accessorKey: 'slug', header: 'Strategy', cell: (c) => <Link to={`/strategies/${c.row.original.id}`}>{c.getValue()}</Link> },
    { accessorKey: 'family', header: 'Family' },
    { accessorKey: 'latestVersion', header: 'Latest', meta: { numeric: true }, cell: (c) => `v${c.getValue()}` },
    { id: 'status', header: 'Status', accessorFn: (s) => s.latestStatus ?? '—', cell: (c) => <Badge>{c.getValue()}</Badge> },
    { id: 'deployments', header: 'Deployments', enableSorting: false, cell: (c) => {
      const deps = (deployments ?? []).filter((d) => d.strategyId === c.row.original.id);
      return deps.length ? deps.map((d) => `${d.mode}${d.enabled ? '' : ' (paused)'}`).join(', ') : '—';
    } },
  ];
  return (
    <Page title="Strategies" actions={<Link to="/lab">Open the Lab to create or edit a strategy</Link>}>
      <DataTable data-testid="strategies-table" columns={columns} data={data} loading={isLoading} empty="No strategies yet" getRowId={(s) => s.id} />
    </Page>
  );
}
