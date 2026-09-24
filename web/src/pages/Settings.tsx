import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Health } from '../api/types';
import { ApiKeysPanel } from '../components/ApiKeysPanel';
import { NotificationsPanel } from '../components/NotificationsPanel';
import { WebhooksPanel } from '../components/WebhooksPanel';

export function Settings() {
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health') });
  return (
    <div>
      <h1>Settings</h1>
      <p>Mode: {health?.mode}</p>
      <p>Version: {health?.version}</p>
      <ApiKeysPanel />
      <NotificationsPanel />
      <WebhooksPanel />
    </div>
  );
}
