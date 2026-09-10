import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiBaseUrl, request } from '../api/client';
import { Webhook, WebhookCreated, WebhookDelivery } from '../api/types';
import { parseInstruments, targetLabel, webhookUrl } from '../lib/webhooks';

function Deliveries({ id }: { id: string }) {
  const { data } = useQuery({ queryKey: ['webhook-deliveries', id], queryFn: () => request<WebhookDelivery[]>(`/webhooks/${id}/deliveries?limit=20`) });
  if (!data?.length) return <p>No deliveries yet.</p>;
  return (
    <ul>
      {data.map((d) => (
        <li key={d.id}>
          {new Date(d.receivedAt).toLocaleString()} <b>{d.status}</b> {d.detail}
        </li>
      ))}
    </ul>
  );
}

/** External webhooks (M5.5, admin): create, enable/disable, rotate the secret (shown once), and the delivery log. */
export function WebhooksPanel() {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [versionId, setVersionId] = useState('');
  const [instruments, setInstruments] = useState('');
  const [authMode, setAuthMode] = useState<'HMAC' | 'PASSPHRASE'>('HMAC');
  const [secret, setSecret] = useState<WebhookCreated | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { data: hooks } = useQuery({ queryKey: ['webhooks'], queryFn: () => request<Webhook[]>('/webhooks'), retry: false });
  const refresh = () => qc.invalidateQueries({ queryKey: ['webhooks'] });
  const create = useMutation({
    mutationFn: () => request<WebhookCreated>('/webhooks', {
      method: 'POST',
      body: { name, authMode, strategyVersionId: versionId.trim() || null, allowedInstruments: parseInstruments(instruments) },
    }),
    onSuccess: (c) => { setSecret(c); setError(null); setName(''); refresh(); },
    onError: (e: Error) => setError(e.message),
  });
  const update = useMutation({
    mutationFn: (w: Webhook) => request<Webhook>(`/webhooks/${w.id}`, { method: 'PUT', body: { enabled: !w.enabled } }),
    onSuccess: refresh,
  });
  const rotate = useMutation({
    mutationFn: (id: string) => request<WebhookCreated>(`/webhooks/${id}/rotate`, { method: 'POST' }),
    onSuccess: (c) => setSecret(c),
  });

  return (
    <section data-testid="webhooks-panel">
      <h2>Webhooks</h2>
      <p>External systems send signal intents, never orders; see docs/webhooks.md for signing and the TradingView template.</p>
      {secret && (
        <div data-testid="webhook-secret" style={{ border: '1px solid #b7791f', padding: 8, margin: '8px 0' }}>
          <b>{secret.webhook.name}</b>: secret <code>{secret.secret}</code> — shown only now. URL <code>{webhookUrl(apiBaseUrl(), secret.webhook.id)}</code>
          <button onClick={() => setSecret(null)} style={{ marginLeft: 8 }}>Done</button>
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <input placeholder="name" value={name} onChange={(e) => setName(e.target.value)} />
        <input placeholder="strategy version id (empty = manual)" value={versionId} onChange={(e) => setVersionId(e.target.value)} style={{ width: 280 }} />
        <input placeholder="allowed instruments (NSE:INFY, …)" value={instruments} onChange={(e) => setInstruments(e.target.value)} />
        <select value={authMode} onChange={(e) => setAuthMode(e.target.value as 'HMAC' | 'PASSPHRASE')}>
          <option value="HMAC">HMAC signature</option>
          <option value="PASSPHRASE">Passphrase (TradingView)</option>
        </select>
        <button onClick={() => create.mutate()} disabled={!name || create.isPending}>Create</button>
      </div>
      {error && <p style={{ color: '#c0392b' }}>{error}</p>}
      <ul>
        {hooks?.map((w) => (
          <li key={w.id} style={{ margin: '8px 0' }}>
            <b>{w.name}</b> · {w.authMode} · {targetLabel(w)} · {w.allowedInstruments.length ? w.allowedInstruments.join(', ') : 'any instrument'} ·{' '}
            {w.enabled ? 'enabled' : 'disabled'}
            {w.lastReceivedAt && <small> · last {new Date(w.lastReceivedAt).toLocaleString()}</small>}{' '}
            <button onClick={() => update.mutate(w)}>{w.enabled ? 'Disable' : 'Enable'}</button>{' '}
            <button onClick={() => rotate.mutate(w.id)}>Rotate secret</button>{' '}
            <button onClick={() => setOpen(open === w.id ? null : w.id)}>Deliveries</button>
            {open === w.id && <Deliveries id={w.id} />}
          </li>
        ))}
      </ul>
    </section>
  );
}
