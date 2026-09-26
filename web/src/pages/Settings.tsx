import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Health } from '../api/types';
import { ApiKeysPanel } from '../components/ApiKeysPanel';
import { NotificationsPanel } from '../components/NotificationsPanel';
import { WebhooksPanel } from '../components/WebhooksPanel';
import { THEME_CHOICES, ThemeChoice, setTheme, storedTheme } from '../lib/theme';

export function Settings() {
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health') });
  const [theme, setThemeChoice] = useState<ThemeChoice>(storedTheme);
  return (
    <div>
      <h1>Settings</h1>
      <p>Mode: {health?.mode}</p>
      <p>Version: {health?.version}</p>
      <h2>Appearance</h2>
      <p>
        <label>
          Theme{' '}
          <select
            data-testid="theme-select"
            value={theme}
            onChange={(e) => { const t = e.target.value as ThemeChoice; setTheme(t); setThemeChoice(t); }}
          >
            {THEME_CHOICES.map((t) => <option key={t} value={t}>{t === 'system' ? 'Follow the system' : t === 'light' ? 'Light' : 'Dark'}</option>)}
          </select>
        </label>{' '}
        <small>Stored in this browser only.</small>
      </p>
      <p><Link to="/design">Design system</Link>: every component in both themes.</p>
      <ApiKeysPanel />
      <NotificationsPanel />
      <WebhooksPanel />
    </div>
  );
}
