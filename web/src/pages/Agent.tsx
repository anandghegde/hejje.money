import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { AiStatus, AiTraceStep, Grounding } from '../api/types';
import { askStream, highlightUnverified } from '../lib/agent';
import { createdProposal } from '../lib/approvals';
import { Badge, BadgeTone, Button, Card, Page } from '../ui';
import '../styles/system.css';

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

const STATUS_TONE: Record<string, BadgeTone> = { OK: 'profit', FORBIDDEN: 'loss', INVALID_INPUT: 'warning', NOT_FOUND: 'warning' };

function Answer({ turn }: { turn: ChatTurn }) {
  const segments = highlightUnverified(turn.answer, turn.grounding?.unverifiedNumbers ?? []);
  const flagged = !!turn.grounding && (turn.grounding.unverifiedNumbers.length > 0 || turn.grounding.unknownIds.length > 0);
  return (
    <div data-testid="ai-answer" className="ai-answer">
      {segments.map((s, i) => s.unverified
        ? <mark key={i} title="Not found in this turn's tool results" data-testid="unverified" className="unverified">{s.text}<sup>unverified</sup></mark>
        : <span key={i}>{s.text}</span>)}
      {!turn.done && <span className="muted"> …</span>}
      {turn.error && <div className="tone-loss">{turn.error}</div>}
      {turn.done && createdProposal(turn.trace) && (
        <div data-testid="proposal-created" className="message ai-proposal">
          Proposal created — <Link to="/approvals">approve in the inbox</Link>. Nothing has been placed yet.
        </div>
      )}
      {turn.done && turn.grounding && (
        <div className={`ai-grounding ${flagged ? 'tone-warning' : 'muted'}`}>
          {flagged
            ? `⚠ ${turn.grounding.unverifiedNumbers.length} number(s) and ${turn.grounding.unknownIds.length} id(s) not found in the tool results`
            : `✓ ${turn.grounding.verifiedNumbers.length} number(s) traced to tool results`}
          {turn.flow ? ` · flow ${turn.flow}` : ''}{turn.stepLimitReached ? ' · step limit reached' : ''}
        </div>
      )}
    </div>
  );
}

function Trace({ steps }: { steps: AiTraceStep[] }) {
  if (!steps.length) return <p className="muted">No tool calls yet.</p>;
  return (
    <div className="table-scroll">
      <table data-testid="ai-trace" className="full-width">
        <thead><tr><th>Tool</th><th>Scope</th><th>Status</th><th className="num">ms</th></tr></thead>
        <tbody>
          {steps.map((s) => (
            <tr key={s.actionId} title={s.error ?? ''}>
              <td><code>{s.tool}</code></td>
              <td>{s.requiredScope ?? '—'}</td>
              <td><Badge tone={STATUS_TONE[s.status] ?? 'neutral'}>{s.status}</Badge></td>
              <td className="num">{s.latencyMs}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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
      <Page title="Hejje AI">
        <Card data-testid="ai-disabled">
          <p>Hejje AI is off: {status.reason}</p>
          <p className="muted">Everything else — signals, scoring, risk, orders — works without it.</p>
        </Card>
      </Page>
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
    <Page title="Hejje AI">
      <div className="agent-grid">
        <Card>
          <div className="stack">
            <p className="muted">Answers come from Hejje's tools; numbers that cannot be traced to a tool result are marked unverified.</p>
            <div className="cluster">
              {QUICK.map((q) => <Button key={q.label} size="sm" disabled={busy} onClick={() => ask(q.question, q.flow)}>{q.label}</Button>)}
              {conversationId && <Button size="sm" disabled={busy} onClick={() => { setConversationId(null); setTurns([]); }}>New conversation</Button>}
            </div>
            {turns.map((t, i) => (
              <div key={i} className="stack-sm">
                <div className="ai-question-text">You: {t.question}</div>
                <Answer turn={t} />
              </div>
            ))}
            <form onSubmit={(e) => { e.preventDefault(); void ask(question); }} className="ai-form">
              <input data-testid="ai-question" aria-label="question" value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="Ask about strategies, the market, your risk…"
                disabled={busy} maxLength={2000} />
              <Button type="submit" variant="primary" disabled={busy || !question.trim()}>{busy ? 'Thinking…' : 'Ask'}</Button>
            </form>
          </div>
        </Card>
        <Card title="Tool calls">
          <div className="stack-sm">
            <Trace steps={latest?.trace ?? []} />
            {status && <p className="muted text-sm">Profiles: {status.profile} / follow-ups {status.followUpProfile} · at most {status.maxSteps} steps</p>}
          </div>
        </Card>
      </div>
    </Page>
  );
}
