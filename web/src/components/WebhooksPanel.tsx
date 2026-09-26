import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiBaseUrl, request } from '../api/client';
import { Webhook, WebhookCreated, WebhookDelivery } from '../api/types';
import { parseInstruments, targetLabel, webhookUrl } from '../lib/webhooks';
import { Badge, Button, Card, Dialog, Field } from '../ui';

function Deliveries({ id }: { id: string }) {
  const { data } = useQuery({ queryKey: ['webhook-deliveries', id], queryFn: () => request<WebhookDelivery[]>(`/webhooks/${id}/deliveries?limit=20`) });
  if (!data?.length) return <p className="muted">No deliveries yet.</p>;
  return (
    <ul className="text-sm">
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
  const [rotating, setRotating] = useState<Webhook | null>(null);
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
    <Card title="Webhooks" data-testid="webhooks-panel">
      <div className="stack">
        <p>External systems send signal intents, never orders; see docs/webhooks.md for signing and the TradingView template.</p>
        {secret && (
          <div data-testid="webhook-secret" className="secret-box stack-sm">
            <div><b>{secret.webhook.name}</b>: secret <code className="wrap">{secret.secret}</code> — shown only now.</div>
            <div>URL <code className="wrap">{webhookUrl(apiBaseUrl(), secret.webhook.id)}</code></div>
            <div><Button size="sm" onClick={() => setSecret(null)}>Done</Button></div>
          </div>
        )}
        <div className="form-row">
          <Field label="Name"><input placeholder="name" value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Strategy version"><input placeholder="strategy version id (empty = manual)" value={versionId} onChange={(e) => setVersionId(e.target.value)} /></Field>
          <Field label="Allowed instruments"><input placeholder="allowed instruments (NSE:INFY, …)" value={instruments} onChange={(e) => setInstruments(e.target.value)} /></Field>
          <Field label="Authentication">
            <select value={authMode} onChange={(e) => setAuthMode(e.target.value as 'HMAC' | 'PASSPHRASE')}>
              <option value="HMAC">HMAC signature</option>
              <option value="PASSPHRASE">Passphrase (TradingView)</option>
            </select>
          </Field>
          <Button variant="primary" onClick={() => create.mutate()} disabled={!name || create.isPending}>Create</Button>
        </div>
        {error && <p className="message message-loss">{error}</p>}
        <ul className="plain-list stack-sm">
          {hooks?.map((w) => (
            <li key={w.id} className="item-card">
              <div className="cluster">
                <b>{w.name}</b> · {w.authMode} · {targetLabel(w)} · {w.allowedInstruments.length ? w.allowedInstruments.join(', ') : 'any instrument'}
                <Badge tone={w.enabled ? 'profit' : 'neutral'}>{w.enabled ? 'enabled' : 'disabled'}</Badge>
                {w.lastReceivedAt && <small className="muted">last {new Date(w.lastReceivedAt).toLocaleString()}</small>}
              </div>
              <div className="cluster">
                <Button size="sm" onClick={() => update.mutate(w)}>{w.enabled ? 'Disable' : 'Enable'}</Button>
                <Button size="sm" variant="danger" onClick={() => setRotating(w)}>Rotate secret</Button>
                <Button size="sm" onClick={() => setOpen(open === w.id ? null : w.id)} aria-expanded={open === w.id}>Deliveries</Button>
              </div>
              {open === w.id && <Deliveries id={w.id} />}
            </li>
          ))}
        </ul>
      </div>
      <Dialog
        open={rotating !== null}
        title={`Rotate the secret of ${rotating?.name ?? 'this webhook'}?`}
        onClose={() => setRotating(null)}
        actions={<>
          <Button onClick={() => setRotating(null)}>Keep it</Button>
          <Button variant="danger" onClick={() => { if (rotating) rotate.mutate(rotating.id); setRotating(null); }}>Rotate</Button>
        </>}
      >
        The old secret stops working at once; senders need the new one.
      </Dialog>
    </Card>
  );
}
