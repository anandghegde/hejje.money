import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { PolicyDecision, PolicyRule, PolicyView } from '../api/types';
import { actionsText, decisionsFor, parseParams, sortRules } from '../lib/policies';
import { Button, Card, Page } from '../ui';
import '../styles/system.css';

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
    <tr data-testid={`policy-${rule.name}`} className={enabled ? undefined : 'row-disabled'}>
      <td><input className="input-xs" type="number" aria-label={`${rule.name} priority`} value={priority} onChange={(e) => setPriority(Number(e.target.value))} /></td>
      <td><b>{rule.name}</b><div className="muted text-sm">{rule.description}</div></td>
      <td>{rule.condition}</td>
      <td>{actionsText(rule)}</td>
      <td>
        <select aria-label={`${rule.name} decision`} value={decision} onChange={(e) => setDecision(e.target.value as PolicyDecision)}>
          {decisionsFor(rule).map((d) => <option key={d} value={d}>{d}</option>)}
        </select>
      </td>
      <td><input className="input-md" aria-label={`${rule.name} params`} aria-invalid={params ? undefined : true} value={paramsText} onChange={(e) => setParamsText(e.target.value)} />
        {!params && <div className="field-error">Not valid JSON</div>}</td>
      <td><input type="checkbox" aria-label={`${rule.name} enabled`} checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /></td>
      <td>
        <Button size="sm" disabled={!dirty || !params} onClick={save}>Save</Button>
        {error && <div className="field-error">{error}</div>}
        <div className="muted text-xs">{rule.updatedBy} · {new Date(rule.updatedAt).toLocaleString()}</div>
      </td>
    </tr>
  );
}

/** PRD 49 approval policies: inspect and edit (risk:write); saving audits POLICY_UPDATED. */
export function Policies() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['policies'], queryFn: () => request<PolicyView>('/risk/policies') });
  if (!data) return <Page title="Approval policies"><p>Loading…</p></Page>;
  return (
    <Page title="Approval policies">
      <Card>
        <ul>{data.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      </Card>
      <Card>
      <div className="table-scroll">
      <table>
        <thead><tr><th>Priority</th><th>Rule</th><th>Condition</th><th>Actions</th><th>Decision</th><th>Params</th><th>On</th><th></th></tr></thead>
        <tbody>
          {sortRules(data.rules).map((r) => (
            <RuleRow key={`${r.id}:${r.updatedAt}`} rule={r} onSaved={() => qc.invalidateQueries({ queryKey: ['policies'] })} />
          ))}
        </tbody>
      </table>
      </div>
      <p className="section">When no rule matches: <b>{data.defaultDecision}</b></p>
      </Card>
    </Page>
  );
}
