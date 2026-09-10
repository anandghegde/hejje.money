import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { PolicyDecision, PolicyRule, PolicyView } from '../api/types';
import { actionsText, decisionsFor, parseParams, sortRules } from '../lib/policies';

function RuleRow({ rule, onSaved }: { rule: PolicyRule; onSaved: () => void }) {
  const [enabled, setEnabled] = useState(rule.enabled);
  const [decision, setDecision] = useState<PolicyDecision>(rule.decision);
  const [priority, setPriority] = useState(rule.priority);
  const [paramsText, setParamsText] = useState(JSON.stringify(rule.params));
  const [error, setError] = useState<string | null>(null);
  const params = parseParams(paramsText);
  const dirty = enabled !== rule.enabled || decision !== rule.decision || priority !== rule.priority || paramsText !== JSON.stringify(rule.params);

  async function save() {
    setError(null);
    try {
      await request(`/risk/policies/${rule.id}`, { method: 'PUT', body: { enabled, decision, priority, params } });
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <tr data-testid={`policy-${rule.name}`} style={{ opacity: enabled ? 1 : 0.5 }}>
      <td><input type="number" value={priority} style={{ width: 56 }} onChange={(e) => setPriority(Number(e.target.value))} /></td>
      <td><b>{rule.name}</b><div style={{ fontSize: 12, color: '#616161' }}>{rule.description}</div></td>
      <td>{rule.condition}</td>
      <td>{actionsText(rule)}</td>
      <td>
        <select value={decision} onChange={(e) => setDecision(e.target.value as PolicyDecision)}>
          {decisionsFor(rule).map((d) => <option key={d} value={d}>{d}</option>)}
        </select>
      </td>
      <td><input value={paramsText} onChange={(e) => setParamsText(e.target.value)} style={{ width: 160, borderColor: params ? undefined : '#c0392b' }} /></td>
      <td><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /></td>
      <td>
        <button disabled={!dirty || !params} onClick={save}>Save</button>
        {error && <div style={{ color: '#c0392b', fontSize: 12 }}>{error}</div>}
        <div style={{ fontSize: 11, color: '#616161' }}>{rule.updatedBy} · {new Date(rule.updatedAt).toLocaleString()}</div>
      </td>
    </tr>
  );
}

/** PRD 49 approval policies: inspect and edit (risk:write); saving audits POLICY_UPDATED. */
export function Policies() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['policies'], queryFn: () => request<PolicyView>('/risk/policies') });
  if (!data) return <p>Loading…</p>;
  return (
    <div>
      <h1>Approval policies</h1>
      <ul>{data.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      <table>
        <thead><tr><th>Priority</th><th>Rule</th><th>Condition</th><th>Actions</th><th>Decision</th><th>Params</th><th>On</th><th></th></tr></thead>
        <tbody>
          {sortRules(data.rules).map((r) => (
            <RuleRow key={`${r.id}:${r.updatedAt}`} rule={r} onSaved={() => qc.invalidateQueries({ queryKey: ['policies'] })} />
          ))}
        </tbody>
      </table>
      <p>When no rule matches: <b>{data.defaultDecision}</b></p>
    </div>
  );
}
