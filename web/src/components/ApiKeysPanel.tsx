import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Access, ACCESS, ApiKeyCreated, ApiKeySummary, createKeyBody, keyState } from '../lib/apiKeys';

/** API keys for the TUI, agents and bots (admin): create (the key is shown once), list and revoke. */
export function ApiKeysPanel() {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [access, setAccess] = useState<Access>('terminal');
  const [days, setDays] = useState('90');
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { data: keys } = useQuery({ queryKey: ['api-keys'], queryFn: () => request<ApiKeySummary[]>('/auth/clients'), retry: false });
  const refresh = () => qc.invalidateQueries({ queryKey: ['api-keys'] });
  const create = useMutation({
    mutationFn: () => request<ApiKeyCreated>('/auth/clients', { method: 'POST', body: createKeyBody(name, access, Number(days) || null) }),
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
    <section data-testid="api-keys-panel">
      <h2>API keys</h2>
      <p>Keys for the terminal (<code>hejje</code>), agents and bots. The key is shown once; Hejje stores only its hash.</p>
      {created && (
        <div data-testid="api-key-created" style={{ border: '1px solid #b7791f', padding: 8, margin: '8px 0' }}>
          <b>{created.name}</b> — copy it now, it is not shown again:
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{exportLine}</pre>
          <button onClick={() => copy(exportLine)}>{copied ? 'Copied' : 'Copy'}</button>{' '}
          <button onClick={() => setCreated(null)}>Done</button>
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <input placeholder="name, e.g. macbook-tui" value={name} onChange={(e) => setName(e.target.value)} />
        <select value={access} onChange={(e) => setAccess(e.target.value as Access)}>
          {ACCESS.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
        </select>
        <label>expires in <input type="number" min={1} value={days} onChange={(e) => setDays(e.target.value)} style={{ width: 64 }} /> days</label>
        <button onClick={() => create.mutate()} disabled={!name.trim() || create.isPending}>Create key</button>
      </div>
      {error && <p style={{ color: '#c0392b' }}>{error}</p>}
      <table>
        <thead><tr><th>Name</th><th>Key</th><th>Scopes</th><th>Created</th><th>Last used</th><th>Expires</th><th>State</th><th /></tr></thead>
        <tbody>
          {(keys ?? []).map((k) => {
            const state = keyState(k);
            return (
              <tr key={k.id}>
                <td>{k.name}</td>
                <td><code>{k.keyPrefix}…</code></td>
                <td>{k.scopes.join(', ')}</td>
                <td>{new Date(k.createdAt).toLocaleDateString()}</td>
                <td>{k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}</td>
                <td>{k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : 'never'}</td>
                <td>{state}</td>
                <td>{state === 'active' && <button onClick={() => revoke.mutate(k.id)}>Revoke</button>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
