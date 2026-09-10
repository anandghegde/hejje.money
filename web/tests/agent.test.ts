import { describe, expect, it, vi } from 'vitest';
import { askStream, highlightUnverified, parseSse } from '../src/lib/agent';

describe('parseSse', () => {
  it('returns complete events and keeps the unfinished remainder', () => {
    const { events, rest } = parseSse('event: tool\ndata: {"tool":"get_pulse"}\n\nevent: delta\ndata: {"step":1,"text":"Hel');
    expect(events).toEqual([{ event: 'tool', data: { tool: 'get_pulse' } }]);
    expect(rest).toBe('event: delta\ndata: {"step":1,"text":"Hel');
    const next = parseSse(rest + 'lo"}\n\n');
    expect(next.events).toEqual([{ event: 'delta', data: { step: 1, text: 'Hello' } }]);
  });
});

describe('askStream', () => {
  it('posts with the event-stream accept header and dispatches events across chunks', async () => {
    const chunks = ['event: tool\ndata: {"tool":"get_market_regime","status":"OK"}\n\nevent: del', 'ta\ndata: {"step":1,"text":"Up"}\n\nevent: done\ndata: {"answer":"Up"}\n\n'];
    const body = new ReadableStream({ start(c) { chunks.forEach((x) => c.enqueue(new TextEncoder().encode(x))); c.close(); } });
    const fetchImpl = vi.fn().mockResolvedValue(new Response(body, { status: 200 }));
    const seen: string[] = [];
    await askStream({ question: 'regime?' }, (e) => seen.push(e.event), fetchImpl as unknown as typeof fetch);
    expect(seen).toEqual(['tool', 'delta', 'done']);
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toMatch(/\/agents\/ai\/ask$/);
    expect(init.headers.Accept).toBe('text/event-stream');
  });

  it('reports an HTTP failure as an error event', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'LLM disabled' }), { status: 503 }));
    const seen: any[] = [];
    await askStream({ question: 'x' }, (e) => seen.push(e), fetchImpl as unknown as typeof fetch);
    expect(seen).toEqual([{ event: 'error', data: { error: 'LLM disabled' } }]);
  });
});

describe('highlightUnverified', () => {
  it('marks whole-number occurrences only', () => {
    const segs = highlightUnverified('Score 97 (not 970 or 1.97), risk 1,500 and 97.', ['97', '1,500']);
    expect(segs.filter((s) => s.unverified).map((s) => s.text)).toEqual(['97', '1,500', '97']);
    expect(segs.map((s) => s.text).join('')).toBe('Score 97 (not 970 or 1.97), risk 1,500 and 97.');
  });

  it('returns the answer untouched when everything is verified', () => {
    expect(highlightUnverified('All good 87', [])).toEqual([{ text: 'All good 87', unverified: false }]);
  });
});
