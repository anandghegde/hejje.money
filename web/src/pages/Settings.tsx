import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Health } from '../api/types';
import { NotificationsPanel } from '../components/NotificationsPanel';
import { WebhooksPanel } from '../components/WebhooksPanel';

export function Settings() {
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health') });
  return (
    <div>
      <h1>Settings</h1>
      <p>Mode: {health?.mode}</p>
      <p>Version: {health?.version}</p>
      <p>API keys are managed via the API (POST /auth/clients). A management UI arrives in a later phase.</p>
      <NotificationsPanel />
      <WebhooksPanel />
    </div>
  );
}
