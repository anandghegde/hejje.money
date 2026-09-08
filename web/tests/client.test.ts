import { describe, expect, it, vi, beforeEach } from 'vitest';
import { request, setAccessToken, ApiError } from '../src/api/client';

describe('api client', () => {
  beforeEach(() => setAccessToken('tok'));

  it('sends bearer token and idempotency key on transactional calls', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: '1' }), { status: 201, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    await request('/orders/intents', { method: 'POST', idempotent: true, body: { a: 1 } });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['Authorization']).toBe('Bearer tok');
    expect(init.headers['Idempotency-Key']).toMatch(/[0-9a-f-]{36}/);
    vi.unstubAllGlobals();
  });

  it('throws ApiError with the problem body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'nope', reasons: ['x'] }), { status: 422, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(request('/orders/intents', { method: 'POST', idempotent: true, retryOn401: false })).rejects.toBeInstanceOf(ApiError);
    vi.unstubAllGlobals();
  });
});
