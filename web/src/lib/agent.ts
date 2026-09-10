import { apiBaseUrl, getAccessToken, tryRefresh } from '../api/client';

export interface SseEvent { event: string; data: any }

/** Splits a server-sent-events buffer into complete events (blank-line separated) and the unfinished remainder. */
export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = [];
  const normalized = buffer.replace(/\r\n/g, '\n');
  const blocks = normalized.split('\n\n');
  const rest = blocks.pop() ?? '';
  for (const block of blocks) {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length === 0) continue;
    const text = data.join('\n');
    let parsed: any = text;
    try { parsed = JSON.parse(text); } catch { /* keep text */ }
    events.push({ event, data: parsed });
  }
  return { events, rest };
}

export interface AskBody { question: string; conversationId?: string | null; flow?: string }

/** POSTs a question to Hejje AI and dispatches its server-sent events (tool, delta, done, error) as they arrive. */
export async function askStream(body: AskBody, onEvent: (e: SseEvent) => void, fetchImpl: typeof fetch = fetch, retried = false): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetchImpl(`${apiBaseUrl()}/agents/ai/ask`, { method: 'POST', headers, credentials: 'include', body: JSON.stringify(body) });
  if (res.status === 401 && !retried && (await tryRefresh())) return askStream(body, onEvent, fetchImpl, true);
  if (!res.ok || !res.body) {
    let detail = `HTTP ${res.status}`;
    try { detail = (await res.json()).detail ?? detail; } catch { /* empty */ }
    onEvent({ event: 'error', data: { error: detail } });
    return;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const { events, rest } = parseSse(buffer);
    buffer = rest;
    events.forEach(onEvent);
  }
  const { events } = parseSse(buffer + '\n\n');
  events.forEach(onEvent);
}

export interface Segment { text: string; unverified: boolean }

/** Splits an answer so every occurrence of an unverified number (not part of a longer number or word) can be marked. */
export function highlightUnverified(answer: string, unverified: string[]): Segment[] {
  if (!unverified.length) return [{ text: answer, unverified: false }];
  const escaped = [...unverified].sort((a, b) => b.length - a.length).map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp(`(?<![\\p{L}\\d_.,])(?:${escaped.join('|')})(?![\\d]|[.,]\\d)`, 'gu');
  const out: Segment[] = [];
  let last = 0;
  for (const m of answer.matchAll(re)) {
    const at = m.index ?? 0;
    if (at > last) out.push({ text: answer.slice(last, at), unverified: false });
    out.push({ text: m[0], unverified: true });
    last = at + m[0].length;
  }
  if (last < answer.length) out.push({ text: answer.slice(last), unverified: false });
  return out;
}
