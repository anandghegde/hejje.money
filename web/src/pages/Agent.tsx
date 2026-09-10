import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { AiStatus, AiTraceStep, Grounding } from '../api/types';
import { askStream, highlightUnverified } from '../lib/agent';
import { createdProposal } from '../lib/approvals';

interface ChatTurn {
  question: string;
  answer: string;
  step: number;
  trace: AiTraceStep[];
  grounding?: Grounding;
  flow?: string | null;
  stepLimitReached?: boolean;
  error?: string;
  done: boolean;
}

const QUICK = [
  { label: 'What is working today?', question: 'What is working today?' },
  { label: 'Why is the top strategy ranked first?', question: 'Why is the top strategy ranked first?', flow: 'why_ranked_first' },
  { label: 'Market regime', question: 'What is the market regime right now and what does it favour?' },
  { label: 'My risk', question: 'Show my open positions and account risk' },
];

const STATUS_COLOR: Record<string, string> = { OK: '#2e7d32', FORBIDDEN: '#c62828', INVALID_INPUT: '#ef6c00', NOT_FOUND: '#ef6c00' };

function Answer({ turn }: { turn: ChatTurn }) {
  const segments = highlightUnverified(turn.answer, turn.grounding?.unverifiedNumbers ?? []);
  return (
    <div data-testid="ai-answer" style={{ background: '#f4f6f8', borderRadius: 6, padding: 12, whiteSpace: 'pre-wrap' }}>
      {segments.map((s, i) => s.unverified
        ? <mark key={i} title="Not found in this turn's tool results" data-testid="unverified" style={{ background: '#ffe0b2' }}>{s.text}<sup>unverified</sup></mark>
        : <span key={i}>{s.text}</span>)}
      {!turn.done && <span style={{ color: '#9e9e9e' }}> …</span>}
      {turn.error && <div style={{ color: '#c62828' }}>{turn.error}</div>}
      {turn.done && createdProposal(turn.trace) && (
        <div data-testid="proposal-created" style={{ marginTop: 8, padding: 8, background: '#fff3e0', borderRadius: 4 }}>
          Proposal created — <Link to="/approvals">approve in the inbox</Link>. Nothing has been placed yet.
        </div>
      )}
      {turn.done && turn.grounding && (
        <div style={{ marginTop: 8, fontSize: 12, color: turn.grounding.unverifiedNumbers.length || turn.grounding.unknownIds.length ? '#e65100' : '#616161' }}>
          {turn.grounding.unverifiedNumbers.length || turn.grounding.unknownIds.length
            ? `⚠ ${turn.grounding.unverifiedNumbers.length} number(s) and ${turn.grounding.unknownIds.length} id(s) not found in the tool results`
            : `✓ ${turn.grounding.verifiedNumbers.length} number(s) traced to tool results`}
          {turn.flow ? ` · flow ${turn.flow}` : ''}{turn.stepLimitReached ? ' · step limit reached' : ''}
        </div>
      )}
    </div>
  );
}

function Trace({ steps }: { steps: AiTraceStep[] }) {
  if (!steps.length) return <p style={{ color: '#9e9e9e' }}>No tool calls yet.</p>;
  return (
    <table data-testid="ai-trace" style={{ fontSize: 13, borderCollapse: 'collapse', width: '100%' }}>
      <thead><tr><th align="left">Tool</th><th align="left">Scope</th><th align="left">Status</th><th align="right">ms</th></tr></thead>
      <tbody>
        {steps.map((s) => (
          <tr key={s.actionId} title={s.error ?? ''}>
            <td><code>{s.tool}</code></td>
            <td>{s.requiredScope ?? '—'}</td>
            <td style={{ color: STATUS_COLOR[s.status] ?? '#616161', fontWeight: 600 }}>{s.status}</td>
            <td align="right">{s.latencyMs}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function Agent() {
  const { data: status } = useQuery({ queryKey: ['ai-status'], queryFn: () => request<AiStatus>('/agents/ai/status') });
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);

  if (status && !status.enabled) {
    return (
      <div data-testid="ai-disabled">
        <h2>Hejje AI</h2>
        <p>Hejje AI is off: {status.reason}</p>
        <p>Everything else — signals, scoring, risk, orders — works without it.</p>
      </div>
    );
  }

  const update = (fn: (t: ChatTurn) => ChatTurn) => setTurns((all) => all.map((t, i) => (i === all.length - 1 ? fn(t) : t)));

  async function ask(q: string, flow?: string) {
    if (!q.trim() || busy) return;
    setBusy(true);
    setQuestion('');
    setTurns((all) => [...all, { question: q, answer: '', step: 0, trace: [], done: false }]);
    await askStream({ question: q, conversationId, flow }, (e) => {
      if (e.event === 'tool') update((t) => ({ ...t, trace: [...t.trace, e.data as AiTraceStep] }));
      else if (e.event === 'delta') update((t) => (e.data.step === t.step ? { ...t, answer: t.answer + e.data.text } : { ...t, step: e.data.step, answer: e.data.text }));
      else if (e.event === 'done') {
        setConversationId(e.data.conversationId);
        update((t) => ({ ...t, answer: e.data.answer, trace: e.data.trace, grounding: e.data.grounding, flow: e.data.flow, stepLimitReached: e.data.stepLimitReached, done: true }));
      } else if (e.event === 'error') update((t) => ({ ...t, error: e.data.error ?? 'Failed', done: true }));
    });
    setBusy(false);
  }

  const latest = turns[turns.length - 1];
  return (
    <div style={{ display: 'flex', gap: 24 }}>
      <div style={{ flex: 2, minWidth: 0 }}>
        <h2>Hejje AI</h2>
        <p style={{ color: '#616161', marginTop: -8 }}>Answers come from Hejje's tools; numbers that cannot be traced to a tool result are marked unverified.</p>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
          {QUICK.map((q) => <button key={q.label} disabled={busy} onClick={() => ask(q.question, q.flow)}>{q.label}</button>)}
          {conversationId && <button disabled={busy} onClick={() => { setConversationId(null); setTurns([]); }}>New conversation</button>}
        </div>
        {turns.map((t, i) => (
          <div key={i} style={{ marginBottom: 16 }}>
            <div style={{ fontWeight: 600, marginBottom: 4 }}>You: {t.question}</div>
            <Answer turn={t} />
          </div>
        ))}
        <form onSubmit={(e) => { e.preventDefault(); void ask(question); }} style={{ display: 'flex', gap: 8 }}>
          <input data-testid="ai-question" value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="Ask about strategies, the market, your risk…"
            style={{ flex: 1, padding: 8 }} disabled={busy} maxLength={2000} />
          <button type="submit" disabled={busy || !question.trim()}>{busy ? 'Thinking…' : 'Ask'}</button>
        </form>
      </div>
      <div style={{ flex: 1, minWidth: 280 }}>
        <h3>Tool calls</h3>
        <Trace steps={latest?.trace ?? []} />
        {status && <p style={{ fontSize: 12, color: '#9e9e9e' }}>Profiles: {status.profile} / follow-ups {status.followUpProfile} · at most {status.maxSteps} steps</p>}
      </div>
    </div>
  );
}
