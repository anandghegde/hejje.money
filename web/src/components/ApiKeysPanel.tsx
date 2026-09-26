import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Access, ACCESS, ApiKeyCreated, ApiKeySummary, BotSummary, createKeyBody, keyState } from '../lib/apiKeys';
import { Badge, Button, Card, Dialog, Field } from '../ui';

/** API keys for the TUI, agents and bots (admin): create (the key is shown once), list and revoke. */
export function ApiKeysPanel() {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [access, setAccess] = useState<Access>('terminal');
  const [days, setDays] = useState('90');
  const [botId, setBotId] = useState('');
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<ApiKeySummary | null>(null);
  const { data: keys } = useQuery({ queryKey: ['api-keys'], queryFn: () => request<ApiKeySummary[]>('/auth/clients'), retry: false });
  const { data: bots } = useQuery({ queryKey: ['bots'], queryFn: () => request<BotSummary[]>('/bots'), retry: false, enabled: access === 'bot' });
  const botName = (id: string) => bots?.find((b) => b.id === id)?.name ?? id.slice(0, 8);
  const refresh = () => qc.invalidateQueries({ queryKey: ['api-keys'] });
  const create = useMutation({
    mutationFn: () => request<ApiKeyCreated>('/auth/clients', { method: 'POST', body: createKeyBody(name, access, Number(days) || null, botId || null) }),
    onSuccess: (c) => { setCreated(c); setCopied(false); setError(null); setName(''); refresh(); },
    onError: (e: Error) => setError(e.message),
  });
  const revoke = useMutation({
    mutationFn: (id: string) => request(`/auth/clients/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
  });

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  const exportLine = created ? `export HEJJE_API_KEY=${created.key}` : '';
  return (
    <Card title="API keys" data-testid="api-keys-panel">
      <div className="stack">
        <p>Keys for the terminal (<code>hejje</code>), agents and bots. The key is shown once; Hejje stores only its hash.</p>
        {created && (
          <div data-testid="api-key-created" className="secret-box stack-sm">
            <div><b>{created.name}</b> — copy it now, it is not shown again:</div>
            <pre className="code-block wrap">{exportLine}</pre>
            <div className="cluster">
              <Button size="sm" onClick={() => copy(exportLine)}>{copied ? 'Copied' : 'Copy'}</Button>
              <Button size="sm" onClick={() => setCreated(null)}>Done</Button>
            </div>
          </div>
        )}
        <div className="form-row">
          <Field label="Name"><input placeholder="name, e.g. macbook-tui" value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Access">
            <select value={access} onChange={(e) => setAccess(e.target.value as Access)}>
              {ACCESS.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
            </select>
          </Field>
          {access === 'bot' && (
            <Field label="Bot" hint="The key decides for this bot only">
              <select data-testid="api-key-bot" value={botId} onChange={(e) => setBotId(e.target.value)}>
                <option value="">bot: pick one</option>
                {(bots ?? []).map((b) => <option key={b.id} value={b.id}>{b.name} v{b.version}</option>)}
              </select>
            </Field>
          )}
          <Field label="Expires in (days)"><input className="input-xs" type="number" min={1} value={days} onChange={(e) => setDays(e.target.value)} /></Field>
          <Button variant="primary" onClick={() => create.mutate()} disabled={!name.trim() || (access === 'bot' && !botId) || create.isPending}>Create key</Button>
        </div>
        {error && <p className="message message-loss">{error}</p>}
        <div className="table-scroll">
          <table>
            <thead><tr><th>Name</th><th>Key</th><th>Scopes</th><th>Created</th><th>Last used</th><th>Expires</th><th>State</th><th /></tr></thead>
            <tbody>
              {(keys ?? []).map((k) => {
                const state = keyState(k);
                return (
                  <tr key={k.id}>
                    <td>{k.name}</td>
                    <td><code>{k.keyPrefix}…</code></td>
                    <td>{k.scopes.join(', ')}{k.botId ? ` · bot ${botName(k.botId)}` : ''}</td>
                    <td>{new Date(k.createdAt).toLocaleDateString()}</td>
                    <td>{k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}</td>
                    <td>{k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : 'never'}</td>
                    <td><Badge tone={state === 'active' ? 'profit' : 'neutral'}>{state}</Badge></td>
                    <td>{state === 'active' && <Button size="sm" variant="danger" onClick={() => setRevoking(k)}>Revoke</Button>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
      <Dialog
        open={revoking !== null}
        title={`Revoke ${revoking?.name ?? 'this key'}?`}
        onClose={() => setRevoking(null)}
        actions={<>
          <Button onClick={() => setRevoking(null)}>Keep it</Button>
          <Button variant="danger" onClick={() => { if (revoking) revoke.mutate(revoking.id); setRevoking(null); }}>Revoke</Button>
        </>}
      >
        Anything using this key stops working at once. This cannot be undone.
      </Dialog>
    </Card>
  );
}
