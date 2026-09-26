import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Health } from '../api/types';
import { ApiKeysPanel } from '../components/ApiKeysPanel';
import { NotificationsPanel } from '../components/NotificationsPanel';
import { WebhooksPanel } from '../components/WebhooksPanel';
import { THEME_CHOICES, ThemeChoice, setTheme, storedTheme } from '../lib/theme';
import { Card, Field, Page } from '../ui';
import '../styles/system.css';

export function Settings() {
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health') });
  const [theme, setThemeChoice] = useState<ThemeChoice>(storedTheme);
  return (
    <Page title="Settings">
      <Card title="Server">
        <dl className="kv">
          <dt>Mode</dt><dd>{health?.mode}</dd>
          <dt>Version</dt><dd>{health?.version}</dd>
        </dl>
      </Card>
      <Card title="Appearance">
        <div className="stack">
          <Field label="Theme" hint="Stored in this browser only.">
            <select
              data-testid="theme-select"
              value={theme}
              onChange={(e) => { const t = e.target.value as ThemeChoice; setTheme(t); setThemeChoice(t); }}
            >
              {THEME_CHOICES.map((t) => <option key={t} value={t}>{t === 'system' ? 'Follow the system' : t === 'light' ? 'Light' : 'Dark'}</option>)}
            </select>
          </Field>
          <p><Link to="/design">Design system</Link>: every component in both themes.</p>
        </div>
      </Card>
      <ApiKeysPanel />
      <NotificationsPanel />
      <WebhooksPanel />
    </Page>
  );
}
